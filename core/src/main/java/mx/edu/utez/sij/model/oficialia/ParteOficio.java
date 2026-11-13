
package mx.edu.utez.sij.model.oficialia;

    public class ParteOficio {

        // Atributos
        private String modalidad;
        private String nombrePromovente;
        private String telefonoPromovente;
        private String correoPromovente;
        private String curpRfcPromovente;
        private String nombreDemandado;
        private String referenciaDemandado;

        // Constructor vacío
        public ParteOficio() {
        }

        // Constructor con parámetros
        public ParteOficio(String modalidad,
                           String nombrePromovente,
                           String telefonoPromovente,
                           String correoPromovente,
                           String curpRfcPromovente,
                           String nombreDemandado,
                           String referenciaDemandado) {

            this.modalidad = modalidad;
            this.nombrePromovente = nombrePromovente;
            this.telefonoPromovente = telefonoPromovente;
            this.correoPromovente = correoPromovente;
            this.curpRfcPromovente = curpRfcPromovente;
            this.nombreDemandado = nombreDemandado;
            this.referenciaDemandado = referenciaDemandado;
        }

        // Getters y Setters

        public String getModalidad() {
            return modalidad;
        }

        public void setModalidad(String modalidad) {
            this.modalidad = modalidad;
        }

        public String getNombrePromovente() {
            return nombrePromovente;
        }

        public void setNombrePromovente(String nombrePromovente) {
            this.nombrePromovente = nombrePromovente;
        }

        public String getTelefonoPromovente() {
            return telefonoPromovente;
        }

        public void setTelefonoPromovente(String telefonoPromovente) {
            this.telefonoPromovente = telefonoPromovente;
        }

        public String getCorreoPromovente() {
            return correoPromovente;
        }

        public void setCorreoPromovente(String correoPromovente) {
            this.correoPromovente = correoPromovente;
        }

        public String getCurpRfcPromovente() {
            return curpRfcPromovente;
        }

        public void setCurpRfcPromovente(String curpRfcPromovente) {
            this.curpRfcPromovente = curpRfcPromovente;
        }

        public String getNombreDemandado() {
            return nombreDemandado;
        }

        public void setNombreDemandado(String nombreDemandado) {
            this.nombreDemandado = nombreDemandado;
        }

        public String getReferenciaDemandado() {
            return referenciaDemandado;
        }

        public void setReferenciaDemandado(String referenciaDemandado) {
            this.referenciaDemandado = referenciaDemandado;
        }
    }
