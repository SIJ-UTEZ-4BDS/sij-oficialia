package mx.edu.utez.sij.core.util;

import com.google.gson.Gson;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class JsonResponseUtil {
    private static final Gson gson = new Gson();

    public static void sendJson(HttpServletResponse response, int statusCode, Object data) throws IOException {
        response.setStatus(statusCode);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(gson.toJson(data));
    }

    public static void sendMessage(HttpServletResponse response, int statusCode, boolean ok, String message) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("ok", ok);
        body.put("mensaje", message);
        sendJson(response, statusCode, body);
    }
}
