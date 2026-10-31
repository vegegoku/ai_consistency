package com.example.payments.security;
import com.example.payments.api.ResponseWriter;
import com.example.payments.domain.ApiFailure;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.util.concurrent.ConcurrentHashMap;
public class RateLimitFilter extends OncePerRequestFilter {
    private record Window(long minute,int count){}
    private final ConcurrentHashMap<String,Window> windows=new ConcurrentHashMap<>();private final int limit;private final ResponseWriter writer;
    public RateLimitFilter(int limit,ResponseWriter writer){this.limit=limit;this.writer=writer;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,java.io.IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null&&auth.isAuthenticated()&&req.getRequestURI().startsWith("/api/")){
            req.setAttribute("principal",auth.getName());
            long minute=System.currentTimeMillis()/60000;
            windows.entrySet().removeIf(e->e.getValue().minute()<minute);
            if(windows.size()>10000&&!windows.containsKey(auth.getName())){writer.writeError(new ApiFailure(429,"RATE_LIMITED"),req,res);return;}
            Window window=windows.compute(auth.getName(),(k,old)->new Window(minute,old==null||old.minute()!=minute?1:old.count()+1));
            if(window.count()>limit){writer.writeError(new ApiFailure(429,"RATE_LIMITED"),req,res);return;}
        }
        chain.doFilter(req,res);
    }
}
