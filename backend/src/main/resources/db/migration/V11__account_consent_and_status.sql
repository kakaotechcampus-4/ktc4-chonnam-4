-- 강사 가입 동의 기록과 쓰지 않는 계정 상태·역할 정리 (ADR 2026-10-12 계정 동의·소유권·민감 조회).

-- 이용약관·개인정보 처리방침 동의. 둘 다 필수라 동의 시각은 하나로 둔다.
-- 이 마이그레이션 전에 가입한 계정은 동의 기록이 없어 세 칸 모두 NULL 로 남는다. 새 가입은 세 칸을 모두 채운다.
-- 탈퇴는 계정 행을 지우므로 동의 기록도 함께 사라진다.
ALTER TABLE user_account
    ADD COLUMN terms_version   VARCHAR(20),
    ADD COLUMN privacy_version VARCHAR(20),
    ADD COLUMN consented_at    TIMESTAMPTZ;

ALTER TABLE user_account
    ADD CONSTRAINT ck_user_account_consent_complete
        CHECK ((terms_version IS NULL AND privacy_version IS NULL AND consented_at IS NULL)
            OR (terms_version IS NOT NULL AND privacy_version IS NOT NULL AND consented_at IS NOT NULL));

-- 운영자·초대·정지 기능이 없고 탈퇴는 행 삭제라, 실제로 쓰는 값만 남긴다.
-- 지금까지 가입 경로는 INSTRUCTOR·ACTIVE 만 저장했다. 다른 값이 있으면 이 단계가 실패해 손으로 확인하게 한다.
ALTER TABLE user_account DROP CONSTRAINT user_account_role_check;
ALTER TABLE user_account DROP CONSTRAINT user_account_status_check;

ALTER TABLE user_account
    ADD CONSTRAINT ck_user_account_role CHECK (role = 'INSTRUCTOR'),
    ADD CONSTRAINT ck_user_account_status CHECK (status = 'ACTIVE');
