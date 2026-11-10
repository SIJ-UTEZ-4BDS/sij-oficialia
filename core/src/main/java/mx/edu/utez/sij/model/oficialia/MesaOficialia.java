package mx.edu.utez.sij.model.oficialia;

public class MesaOficialia {

    private int idMesa;
    private int idMateria;
    private int idJuzgado;
    private String nombreMesa;
    private int cargaActual;
    private boolean disponible;

    public MesaOficialia() {
    }

    public MesaOficialia(int idMesa, int idMateria, int idJuzgado,
                         String nombreMesa, int cargaActual,
                         boolean disponible) {
        this.idMesa = idMesa;
        this.idMateria = idMateria;
        this.idJuzgado = idJuzgado;
        this.nombreMesa = nombreMesa;
        this.cargaActual = cargaActual;
        this.disponible = disponible;
    }

    public int getIdMesa() {
        return idMesa;
    }

    public void setIdMesa(int idMesa) {
        this.idMesa = idMesa;
    }

    public int getIdMateria() {
        return idMateria;
    }

    public void setIdMateria(int idMateria) {
        this.idMateria = idMateria;
    }

    public int getIdJuzgado() {
        return idJuzgado;
    }

    public void setIdJuzgado(int idJuzgado) {
        this.idJuzgado = idJuzgado;
    }

    public String getNombreMesa() {
        return nombreMesa;
    }

    public void setNombreMesa(String nombreMesa) {
        this.nombreMesa = nombreMesa;
    }

    public int getCargaActual() {
        return cargaActual;
    }

    public void setCargaActual(int cargaActual) {
        this.cargaActual = cargaActual;
    }

    public boolean isDisponible() {
        return disponible;
    }

    public void setDisponible(boolean disponible) {
        this.disponible = disponible;
    }
}
