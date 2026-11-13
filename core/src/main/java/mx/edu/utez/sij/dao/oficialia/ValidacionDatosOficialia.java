package mx.edu.utez.sij.dao.oficialia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;

public final class ValidacionDatosOficialia {

    private static final Set<String> ESTADOS_CURP = Set.of(
            "AS", "BC", "BS", "CC", "CL", "CM", "CS", "CH", "DF", "DG",
            "GT", "GR", "HG", "JC", "MC", "MN", "MS", "NT", "NL", "OC",
            "PL", "QT", "QR", "SP", "SL", "SR", "TC", "TS", "TL", "VZ",
            "YN", "ZS", "NE");
    private static final String VALORES_CURP =
            "0123456789ABCDEFGHIJKLMNÑOPQRSTUVWXYZ";
    private static final String VALORES_RFC =
            "0123456789ABCDEFGHIJKLMN&OPQRSTUVWXYZ Ñ";

    private ValidacionDatosOficialia() {
    }

    public static boolean identificadorValido(String valor) {
        return identificadorValido(valor, "");
    }

    public static boolean identificadorValido(
            String valor,
            String tipoPersona) {
        String normalizado = valor == null ? "" : valor.trim().toUpperCase(
                java.util.Locale.ROOT);
        String tipo = tipoPersona == null
                ? "" : tipoPersona.trim().toUpperCase(
                        java.util.Locale.ROOT);
        if (!tipo.isEmpty()
                && !"FISICA".equals(tipo) && !"MORAL".equals(tipo)) {
            return false;
        }
        if (normalizado.isEmpty()) {
            return true;
        }
        if (curpValida(normalizado)) {
            return !"MORAL".equals(tipo);
        }
        if (!rfcValido(normalizado)) {
            return false;
        }
        return tipo.isEmpty()
                || ("FISICA".equals(tipo) && normalizado.length() == 13)
                || ("MORAL".equals(tipo) && normalizado.length() == 12);
    }

    public static String normalizarTelefono(String valor) {
        String telefono = valor == null ? "" : valor.trim();
        if (!telefono.matches("[+\\d\\s().-]+")) {
            return "";
        }
        String digitos = telefono.replaceAll("\\D", "");
        if (digitos.length() == 12 && digitos.startsWith("52")) {
            digitos = digitos.substring(2);
        }
        return digitos.matches("\\d{10}") ? digitos : "";
    }

    public static boolean textoDireccionValido(String valor) {
        String texto = valor == null ? "" : valor.trim();
        return texto.length() >= 2
                && texto.matches(
                "(?U)[\\p{L}\\p{M}0-9][\\p{L}\\p{M}0-9 .,'/#()-]*")
                && texto.matches("(?U).*\\p{L}.*");
    }

    public static boolean numeroDomicilioValido(String valor) {
        return valor != null && valor.trim().matches(
                "(?i)[A-Z0-9#][A-Z0-9#./ -]{0,19}");
    }

    public static boolean correoValido(String valor) {
        if (valor == null || valor.trim().length() > 254) {
            return false;
        }
        String[] partes = valor.trim().split("@", -1);
        if (partes.length != 2 || partes[0].length() > 64
                || partes[0].startsWith(".") || partes[0].endsWith(".")
                || partes[0].contains("..")
                || !partes[0].matches(
                "(?i)[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+")) {
            return false;
        }
        String[] dominios = partes[1].split("\\.", -1);
        if (dominios.length < 2
                || dominios[dominios.length - 1].length() < 2) {
            return false;
        }
        for (String dominio : dominios) {
            if (dominio.isEmpty() || dominio.length() > 63
                    || !dominio.matches(
                    "(?i)[A-Z0-9](?:[A-Z0-9-]*[A-Z0-9])?")) {
                return false;
            }
        }
        return true;
    }

    public static void validarCaptura(
            JsonObject partes,
            String direccion,
            String descripcion) {
        JsonElement anonimaElement = partes.get("denunciaAnonima");
        boolean denunciaAnonima = anonimaElement != null
                && anonimaElement.isJsonPrimitive()
                && anonimaElement.getAsJsonPrimitive().isBoolean()
                && anonimaElement.getAsBoolean();
        if (anonimaElement != null
                && (!anonimaElement.isJsonPrimitive()
                || !anonimaElement.getAsJsonPrimitive().isBoolean())) {
            throw new IllegalArgumentException(
                    "La opción de denuncia anónima no es válida.");
        }

        JsonElement promoventeElement = partes.get("promovente");
        JsonElement demandadoElement = partes.get("demandado");
        if (promoventeElement == null || !promoventeElement.isJsonObject()
                || demandadoElement == null || !demandadoElement.isJsonObject()) {
            throw new IllegalArgumentException(
                    "Los datos del promovente y demandado son obligatorios.");
        }
        JsonObject promovente = promoventeElement.getAsJsonObject();
        if (!denunciaAnonima
                && texto(promovente.get("nombreCompleto")).isBlank()) {
            throw new IllegalArgumentException(
                    "El nombre del promovente es obligatorio.");
        }
        JsonObject demandado = demandadoElement.getAsJsonObject();
        if (texto(demandado.get("nombreCompleto")).isBlank()) {
            throw new IllegalArgumentException(
                    "El nombre del demandado es obligatorio.");
        }
        JsonElement tipoPersonaElemento = promovente.get("tipoPersona");
        if (tipoPersonaElemento != null
                && !tipoPersonaElemento.isJsonNull()
                && (!tipoPersonaElemento.isJsonPrimitive()
                || !tipoPersonaElemento.getAsJsonPrimitive().isString())) {
            throw new IllegalArgumentException(
                    "El tipo de persona del promovente no es válido.");
        }
        String tipoPersona = texto(tipoPersonaElemento);
        if (!tipoPersona.isBlank()
                && !"FISICA".equalsIgnoreCase(tipoPersona)
                && !"MORAL".equalsIgnoreCase(tipoPersona)) {
            throw new IllegalArgumentException(
                    "El tipo de persona del promovente no es válido.");
        }
        validarIdentificador(
                texto(promovente.get("curpRfc")), tipoPersona);
        validarIdentificador(texto(demandado.get("curpRfc")), "");

        JsonElement contactoElement = promovente.get("contacto");
        if (contactoElement == null || !contactoElement.isJsonObject()) {
            throw new IllegalArgumentException(
                    "Se requiere un teléfono o correo electrónico de contacto.");
        }
        JsonObject contacto = contactoElement.getAsJsonObject();
        String telefonoOriginal = texto(contacto.get("telefono"));
        String telefono = normalizarTelefono(telefonoOriginal);
        if (!telefonoOriginal.isBlank() && telefono.isBlank()) {
            throw new IllegalArgumentException(
                    "El teléfono debe tener diez dígitos, con prefijo +52 opcional.");
        }
        String correo = texto(contacto.get("correoElectronico"))
                .toLowerCase(java.util.Locale.ROOT);
        if (!correo.isBlank() && !correoValido(correo)) {
            throw new IllegalArgumentException(
                    "El correo electrónico no tiene un formato válido.");
        }
        if (telefono.isBlank() && correo.isBlank()) {
            throw new IllegalArgumentException(
                    "Proporciona un teléfono válido o un correo electrónico válido.");
        }
        contacto.addProperty("telefono", telefono);
        contacto.addProperty("correoElectronico", correo);

        JsonElement direccionElement = partes.get("direccionComponentes");
        if (direccionElement == null || !direccionElement.isJsonObject()) {
            throw new IllegalArgumentException(
                    "La dirección debe incluir sus componentes.");
        }
        JsonObject componentes = direccionElement.getAsJsonObject();
        String[] requeridos = {
                "calle", "numeroExterior", "colonia", "municipio",
                "estado", "codigoPostal"
        };
        for (String campo : requeridos) {
            if (texto(componentes.get(campo)).isBlank()) {
                throw new IllegalArgumentException(
                        "Completa todos los campos obligatorios de la dirección.");
            }
        }
        if (!textoDireccionValido(texto(componentes.get("calle")))
                || !numeroDomicilioValido(
                texto(componentes.get("numeroExterior")))
                || !textoDireccionValido(texto(componentes.get("colonia")))
                || !textoDireccionValido(texto(componentes.get("municipio")))
                || !textoDireccionValido(texto(componentes.get("estado")))
                || !"MORELOS".equalsIgnoreCase(
                texto(componentes.get("estado")))
                || (!texto(componentes.get("numeroInterior")).isBlank()
                && !numeroDomicilioValido(
                texto(componentes.get("numeroInterior"))))
                || !texto(componentes.get("codigoPostal")).matches("\\d{5}")
                || direccion == null || direccion.isBlank()
                || descripcion == null || descripcion.isBlank()) {
            throw new IllegalArgumentException(
                    "La dirección, el código postal o la descripción no son válidos.");
        }
    }

    private static void validarIdentificador(
            String valor,
            String tipoPersona) {
        if (!identificadorValido(valor, tipoPersona)) {
            throw new IllegalArgumentException(
                    "El RFC o CURP de las partes no es válido.");
        }
    }

    private static String texto(JsonElement elemento) {
        return elemento == null || !elemento.isJsonPrimitive()
                ? "" : elemento.getAsString().trim();
    }

    private static boolean curpValida(String curp) {
        if (!curp.matches(
                "^[A-Z][AEIOUX][A-Z]{2}\\d{6}[HM][A-Z]{2}"
                        + "[B-DF-HJ-NP-TV-Z]{3}[A-Z0-9]\\d$")) {
            return false;
        }
        String estado = curp.substring(11, 13);
        if (!ESTADOS_CURP.contains(estado) || !fechaValida(curp, 4)) {
            return false;
        }
        int suma = 0;
        for (int indice = 0; indice < 17; indice++) {
            int valorCaracter = VALORES_CURP.indexOf(curp.charAt(indice));
            if (valorCaracter < 0) {
                return false;
            }
            suma += valorCaracter * (18 - indice);
        }
        return (10 - suma % 10) % 10 == curp.charAt(17) - '0';
    }

    private static boolean rfcValido(String rfc) {
        if ("XAXX010101000".equals(rfc)
                || "XEXX010101000".equals(rfc)) {
            return true;
        }
        if (!rfc.matches("^[A-ZÑ&]{3,4}\\d{6}[A-Z0-9]{2}[0-9A]$")) {
            return false;
        }
        int fechaOffset = rfc.length() == 12 ? 3 : 4;
        if (!fechaValida(rfc, fechaOffset)) {
            return false;
        }
        String normalizado = rfc.length() == 12 ? " " + rfc : rfc;
        int suma = 0;
        for (int indice = 0; indice < 12; indice++) {
            int valorCaracter = VALORES_RFC.indexOf(normalizado.charAt(indice));
            if (valorCaracter < 0) {
                return false;
            }
            suma += valorCaracter * (13 - indice);
        }
        int residuo = suma % 11;
        String verificador = residuo == 0 ? "0"
                : residuo == 1 ? "A" : String.valueOf(11 - residuo);
        return verificador.equals(rfc.substring(rfc.length() - 1));
    }

    private static boolean fechaValida(String valor, int offset) {
        int anio = Integer.parseInt(valor.substring(offset, offset + 2));
        int mes = Integer.parseInt(valor.substring(offset + 2, offset + 4));
        int dia = Integer.parseInt(valor.substring(offset + 4, offset + 6));
        if (mes < 1 || mes > 12 || dia < 1) {
            return false;
        }
        int[] diasMes = {
                31, anio % 4 == 0 ? 29 : 28, 31, 30, 31, 30,
                31, 31, 30, 31, 30, 31
        };
        return dia <= diasMes[mes - 1];
    }
}
