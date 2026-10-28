package com.neuringo.neuringobe.child.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * 아동 입장 세션 저장소. 서버 세션으로는 아동만 인증된다.
 *
 * <p>강사는 요청마다 Bearer 토큰으로 인증하므로 세션에 강사 인증이 있을 이유가 없다. 어떤 경로로든 들어가 있으면 토큰 폐기·만료를 거치지 않고 쿠키만으로 강사 API
 * 가 열리게 되므로, 꺼낼 때 아동이 아니면 인증이 없는 것으로 본다.
 */
public class ChildSessionSecurityContextRepository extends HttpSessionSecurityContextRepository {

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        return new ChildOnlyContext(super.loadDeferredContext(request));
    }

    private static final class ChildOnlyContext implements DeferredSecurityContext {

        private final DeferredSecurityContext stored;
        private SecurityContext context;
        private boolean generated;

        private ChildOnlyContext(DeferredSecurityContext stored) {
            this.stored = stored;
        }

        @Override
        public SecurityContext get() {
            resolve();
            return context;
        }

        @Override
        public boolean isGenerated() {
            resolve();
            return generated;
        }

        private void resolve() {
            if (context != null) {
                return;
            }
            SecurityContext loaded = stored.get();
            Authentication authentication = loaded.getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof ChildPrincipal) {
                context = loaded;
                generated = stored.isGenerated();
            } else {
                // 빈 컨텍스트를 새로 만든 것으로 알려야 앞뒤 저장소를 묶은 쪽이 이 값을 "저장된 인증"으로 쓰지 않는다.
                context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
                generated = true;
            }
        }
    }
}
