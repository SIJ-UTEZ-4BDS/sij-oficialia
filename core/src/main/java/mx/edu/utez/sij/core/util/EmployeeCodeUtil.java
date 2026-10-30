package mx.edu.utez.sij.core.util;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.Year;
import java.util.concurrent.ThreadLocalRandom;

public class EmployeeCodeUtil {
    private static final SecureRandom random = new SecureRandom();

    public static String generateCode(String rol, String nombreCompleto) {
        String prefix = switch (rol != null ? rol.toUpperCase() : "") {
            case "SUPER_ADMIN" -> "ROOT";
            case "ADMIN_OFICIALIA" -> "ADMO";
            case "ADMIN_AUDIENCIAS" -> "ADMA";
            case "ADMIN_TOCAS" -> "ADMT";
            case "ADMIN_PERITOS" -> "ADMP";
            case "OPERADOR_OFICIALIA", "SECRETARIO_OFICIALIA" -> "OFIC";
            case "JUEZ_AUDIENCIA", "ENCARGADO_SALA" -> "AUDI";
            case "MAGISTRADO_TOCAS", "SECRETARIO_ACUERDOS_TOCAS" -> "TOCA";
            case "PERITO_JUDICIAL", "PERITO" -> "PERI";
            default -> "SIJM";
        };

        StringBuilder initials = new StringBuilder();
        if (nombreCompleto != null && !nombreCompleto.isBlank()) {
            for (String part : nombreCompleto.trim().split("\\s+")) {
                if (!part.isEmpty() && Character.isLetter(part.charAt(0))) {
                    initials.append(Character.toUpperCase(part.charAt(0)));
                }
            }
        }
        String ini = initials.isEmpty() ? "XX" : initials.substring(0, Math.min(initials.length(), 4));
        int year = Year.now().getValue();
        int randomNum = ThreadLocalRandom.current().nextInt(10_000, 1_000_000);

        return prefix + "-" + ini + "-" + year + "-" + randomNum;
    }

    public static String generarClave(String rol, String nombreCompleto) {
        return generateCode(rol, nombreCompleto);
    }

    public static String generateEmployeeCode(String rol, String nombreCompleto, int idConsecutivo) {
        String prefix = "SIJ";
        if (rol != null) {
            if (rol.contains("OFICIALIA")) prefix = "OFI";
            else if (rol.contains("AUDIENCIA")) prefix = "AUD";
            else if (rol.contains("TOCA") || rol.contains("MAGISTRADO")) prefix = "TOC";
            else if (rol.contains("PERITO")) prefix = "PER";
        }

        StringBuilder initials = new StringBuilder();
        if (nombreCompleto != null && !nombreCompleto.isBlank()) {
            for (String part : nombreCompleto.trim().split("\\s+")) {
                if (!part.isEmpty()) {
                    initials.append(Character.toUpperCase(part.charAt(0)));
                }
            }
        }
        String initialsText = initials.length() > 0 ? initials.toString() : "EMP";
        return String.format("%s-%s-%02d-%04d", prefix, initialsText, LocalDate.now().getYear() % 100, idConsecutivo);
    }

    public static String generateSixDigitCode() {
        int code = 100_000 + random.nextInt(900_000);
        return String.valueOf(code);
    }
}