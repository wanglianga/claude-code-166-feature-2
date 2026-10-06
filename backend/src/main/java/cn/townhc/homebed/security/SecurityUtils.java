package cn.townhc.homebed.security;

import cn.townhc.homebed.common.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {
    private SecurityUtils() {}

    public static LoginUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LoginUser u)) {
            throw new BusinessException(401, "未登录或登录已过期");
        }
        return u;
    }

    /** 无登录上下文（如数据初始化内部调用）时返回 null，不抛异常 */
    public static LoginUser currentOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser u) return u;
        return null;
    }
}
