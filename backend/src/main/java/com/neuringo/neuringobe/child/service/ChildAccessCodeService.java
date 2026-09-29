package com.neuringo.neuringobe.child.service;

import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.domain.ChildAccessCode;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.dto.AccessCodeResponse;
import com.neuringo.neuringobe.child.dto.ChildAccessSessionResponse;
import com.neuringo.neuringobe.child.repository.ChildAccessCodeRepository;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.classroom.repository.ClassroomRepository;
import com.neuringo.neuringobe.common.ApiDomainException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChildAccessCodeService {

    private static final int CODE_SPACE = 10_000;
    private static final Duration CODE_LIFETIME = Duration.ofHours(24);

    private final ChildRepository children;
    private final ClassroomRepository classrooms;
    private final ChildAccessCodeRepository codes;
    private final AccessCodeCryptography cryptography;
    private final TransactionTemplate transaction;

    public ChildAccessCodeService(
            ChildRepository children,
            ClassroomRepository classrooms,
            ChildAccessCodeRepository codes,
            AccessCodeCryptography cryptography,
            PlatformTransactionManager transactionManager) {
        this.children = children;
        this.classrooms = classrooms;
        this.codes = codes;
        this.cryptography = cryptography;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public IssuedCode issue(UUID childId, UUID requestKey, String instructorId) {
        for (int counter = 0; counter < CODE_SPACE; counter++) {
            int attempt = counter;
            try {
                IssuedCode issued =
                        transaction.execute(
                                ignored -> issueOnce(childId, requestKey, instructorId, attempt));
                if (issued != null) {
                    return issued;
                }
            } catch (DataIntegrityViolationException ex) {
                if (!isUniqueConflict(ex)) {
                    throw ex;
                }
                // A concurrent issuer won a uniqueness race. A fresh transaction tries again.
                // Same-key requests are resolved through the idempotency row on the next pass.
            }
        }
        throw new ApiDomainException(
                HttpStatus.CONFLICT, "ACCESS_CODE_SPACE_EXHAUSTED", "발급 가능한 입장 코드가 없습니다.");
    }

    private IssuedCode issueOnce(UUID childId, UUID requestKey, String instructorId, int counter) {
        Child child =
                children.findByIdForUpdate(childId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "CHILD_NOT_FOUND", "아동을 찾을 수 없습니다."));
        boolean owned =
                classrooms
                        .findById(child.getClassId())
                        .map(classroom -> classroom.getInstructorId().equals(instructorId))
                        .orElse(false);
        if (!owned) {
            throw new ResourceNotFoundException("CHILD_NOT_FOUND", "아동을 찾을 수 없습니다.");
        }

        ChildAccessCode prior = codes.findByIdempotencyKey(requestKey).orElse(null);
        if (prior != null) {
            if (!prior.getChildId().equals(childId)
                    || !prior.getInstructorId().equals(instructorId)) {
                throw new ApiDomainException(
                        HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "다른 발급 요청에 사용한 키입니다.");
            }
            String original =
                    cryptography.deriveCode(childId, requestKey, prior.getDerivationCounter());
            return new IssuedCode(
                    prior.getCodeId(),
                    new AccessCodeResponse(original, koreanTime(prior.getExpiresAt())));
        }
        if (child.getStatus() != ChildStatus.ACTIVE) {
            throw new ApiDomainException(
                    HttpStatus.CONFLICT, "CHILD_NOT_ACTIVE", "활성 상태의 아동만 코드를 발급할 수 있습니다.");
        }

        Instant now = Instant.now();
        codes.deactivateExpired(now);
        ChildAccessCode active = codes.findByChildIdAndActiveTrue(childId).orElse(null);
        String candidate = cryptography.deriveCode(childId, requestKey, counter);
        String digest = cryptography.digest(candidate);
        if ((active != null && active.getCodeDigest().equals(digest))
                || codes.findByCodeDigestAndActiveTrue(digest).isPresent()) {
            return null;
        }
        if (active != null) {
            active.deactivate();
            codes.saveAndFlush(active);
        }
        ChildAccessCode created =
                new ChildAccessCode(
                        UUID.randomUUID(),
                        childId,
                        instructorId,
                        requestKey,
                        digest,
                        counter,
                        now,
                        now.plus(CODE_LIFETIME));
        codes.saveAndFlush(created);
        return new IssuedCode(
                created.getCodeId(),
                new AccessCodeResponse(candidate, koreanTime(created.getExpiresAt())));
    }

    @Transactional
    public ChildAccessSessionResponse enter(String code) {
        if (code == null || !code.matches("[0-9]{4}")) {
            throw new ApiDomainException(
                    HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_CODE", "입장 코드가 올바르지 않습니다.");
        }
        ChildAccessCode found =
                codes.findByCodeDigestAndActiveTrue(cryptography.digest(code))
                        .orElseThrow(
                                () ->
                                        new ApiDomainException(
                                                HttpStatus.UNAUTHORIZED,
                                                "INVALID_ACCESS_CODE",
                                                "입장 코드가 올바르지 않습니다."));
        if (!found.getExpiresAt().isAfter(Instant.now())) {
            throw new ApiDomainException(
                    HttpStatus.UNAUTHORIZED, "ACCESS_CODE_EXPIRED", "입장 코드가 만료되었습니다.");
        }
        Child child =
                children.findByIdForUpdate(found.getChildId())
                        .orElseThrow(
                                () ->
                                        new ApiDomainException(
                                                HttpStatus.UNAUTHORIZED,
                                                "INVALID_ACCESS_CODE",
                                                "입장 코드가 올바르지 않습니다."));
        if (child.getStatus() != ChildStatus.ACTIVE
                || codes.findByCodeDigestAndActiveTrue(cryptography.digest(code)).isEmpty()) {
            throw new ApiDomainException(
                    HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_CODE", "입장 코드가 올바르지 않습니다.");
        }
        return new ChildAccessSessionResponse(child.getChildId(), child.getDisplayName());
    }

    private OffsetDateTime koreanTime(Instant instant) {
        return instant.atOffset(ZoneOffset.ofHours(9));
    }

    private boolean isUniqueConflict(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    public record IssuedCode(UUID codeId, AccessCodeResponse response) {}
}
