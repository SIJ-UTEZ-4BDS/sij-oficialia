package mx.edu.utez.sij.core.filter;

import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

@WebFilter("/*")
public class AuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        String path = req.getRequestURI().substring(req.getContextPath().length());

        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        resp.setHeader("Pragma", "no-cache");
        resp.setDateHeader("Expires", 0);

        boolean isPublic = path.equals("/") ||
                path.equals("/index.html") ||
                path.equals("/index.jsp") ||
                path.startsWith("/assets/") ||
                path.startsWith("/api/auth/");

        if (isPublic) {
            chain.doFilter(request, response);
            return;
        }

        HttpSession session = req.getSession(false);
        String rol = (session != null) ? (String) session.getAttribute("rol") : null;

        if (rol == null) {
            if (path.startsWith("/api/")) {
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write("{\"ok\":false,\"mensaje\":\"Sesión expirada o no iniciada.\"}");
            } else {
                resp.sendRedirect(req.getContextPath() + "/index.html");
            }
            return;
        }

        boolean isAdminRoute = path.startsWith("/views/admin/") || path.startsWith("/api/admin/");
        boolean isManagerRole = rol.equals("SUPER_ADMIN") || rol.startsWith("ADMIN_");

        if (isAdminRoute && !isManagerRole) {
            if (path.startsWith("/api/")) {
                resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write("{\"ok\":false,\"mensaje\":\"Acceso denegado: requiere nivel de Administrador.\"}");
            } else {
                resp.sendRedirect(req.getContextPath() + "/views/dashboard/index.html");
            }
            return;
        }

        if (path.startsWith("/views/dashboard/") && rol.equals("SUPER_ADMIN")) {
            resp.sendRedirect(req.getContextPath() + "/views/admin/index.html");
            return;
        }

        chain.doFilter(request, response);
    }
}