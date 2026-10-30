package mx.edu.utez.sij.core.util;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.InputStream;
import java.util.Properties;

public class EmailService {

    private static final Properties config = new Properties();

    static {
        try (InputStream is = EmailService.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (is != null) config.load(is);
        } catch (Exception e) {
            System.err.println("[SIJ-Core] Error cargando db.properties en EmailService: " + e.getMessage());
        }
    }

    public static boolean sendRecoveryCode(String recipientEmail, String claveEmpleado, String recoveryCode) {
        // Siempre lo mostramos en consola de desarrollo para pruebas locales rápidas:
        System.out.println("==================================================");
        System.out.println("[SIJ-Recuperacion] Empleado: " + claveEmpleado + " (" + recipientEmail + ")");
        System.out.println("[SIJ-Recuperacion] Codigo OTP (6 digitos): " + recoveryCode);
        System.out.println("==================================================");

        String smtpUser = config.getProperty("mail.smtp.user", "");
        String smtpPass = config.getProperty("mail.smtp.password", "");

        if (smtpUser.isBlank() || smtpUser.contains("tu_correo")) {
            return true; // Modo local de pruebas sin credenciales SMTP configuradas
        }

        Properties mailProps = new Properties();
        mailProps.put("mail.smtp.auth", "true");
        mailProps.put("mail.smtp.starttls.enable", "true");
        mailProps.put("mail.smtp.host", config.getProperty("mail.smtp.host", "smtp.gmail.com"));
        mailProps.put("mail.smtp.port", config.getProperty("mail.smtp.port", "587"));

        Session session = Session.getInstance(mailProps, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(smtpUser, smtpPass);
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(smtpUser));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientEmail));
            message.setSubject("SIJ Morelos - Código de Recuperación de Acceso");
            message.setText("Estimado servidor público (" + claveEmpleado + "),\n\n"
                    + "Su código temporal para restablecer su contraseña en el Sistema Integral Judicial es:\n\n"
                    + "    " + recoveryCode + "\n\n"
                    + "Este código expira en 15 minutos.");
            Transport.send(message);
            return true;
        } catch (MessagingException e) {
            System.err.println("[SIJ-Core] Error al enviar correo SMTP: " + e.getMessage());
            return false;
        }
    }
}