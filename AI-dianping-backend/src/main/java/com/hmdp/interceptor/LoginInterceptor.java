package com.hmdp.interceptor;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpStatus;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class LoginInterceptor implements HandlerInterceptor {
    //ctrl+o可以选择重写的方法

    static boolean isPublicDocumentationPath(String requestUri, String contextPath) {
        if (requestUri == null || contextPath == null || !requestUri.startsWith(contextPath)) {
            return false;
        }
        String path = requestUri.substring(contextPath.length());
        return path.equals("/swagger-ui") || path.startsWith("/swagger-ui/")
                || path.equals("/swagger-ui.html") || path.equals("/v3/api-docs")
                || path.startsWith("/v3/api-docs/") || path.startsWith("/webjars/");
    }

    //在目标方法执行之前执行
    //返回值表示是否放行 true放行 false不放行
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Keep API documentation public even if an MVC path-pattern exclusion
        // does not match a trailing slash or a SpringDoc redirect target.
        if (isPublicDocumentationPath(request.getRequestURI(), request.getContextPath())) {
            return true;
        }
        // 1.判断ThreadLocal中有没有用户
        UserDTO user = UserHolder.getUser();
        // 2.没有，拦截，返回401状态码
        if(user == null){
            // 2.没有，拦截，返回401状态码
            response.setStatus(HttpStatus.HTTP_UNAUTHORIZED);
            return false;
        }
        UserHolder.saveUser(user);
        // 3.有，放行
        return true;
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 为了防止内存泄漏和线程复用时的数据污染
        UserHolder.removeUser();
    }
}

