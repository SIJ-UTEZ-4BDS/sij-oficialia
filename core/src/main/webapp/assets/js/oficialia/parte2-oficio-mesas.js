"use strict";

document.addEventListener("DOMContentLoaded", () => {
    const API = "../../api/oficialia/mesas-oficio";
    const form = document.getElementById("form-oficio");
    const formElements = {
        demandado: document.getElementById("nombre-demandado"),
        rfcDemandado: document.getElementById("rfc-demandado"),
        materia: document.getElementById("idMateria"),
        materiaTexto: document.getElementById("materia-mostrada"),
        direccion: document.getElementById("direccion"),
        descripcion: document.getElementById("descripcion"),
        anonima: document.getElementById("check-denuncia-anonima"),
        promovente: document.getElementById("resumen-partes")
    };
    const btnGuardar = document.getElementById("btn-guardar");
    const modalConfirmacion = new bootstrap.Modal(
        document.getElementById("modal-confirmacion")
    );
    const modalExito = new bootstrap.Modal(document.getElementById("modal-exito"));
    const datosValidados = sessionStorage.getItem("oficialia_parte1_validada");

    let parte1 = null;
    let enviando = false;

    recuperarParte1();
    inicializarSesion().catch(error => mostrarError(error.message));

    function recuperarParte1() {
        if (!datosValidados) {
            mostrarErrorParte1("No se encontraron datos validados de la Parte 1.");
            return;
        }
        try {
            parte1 = JSON.parse(datosValidados);
            if (!parte1 || !parte1.promovente || !parte1.idMateria ||
                !parte1.promovente.nombreCompleto) {
                throw new Error("Los datos validados de la Parte 1 están incompletos.");
            }

            formElements.materia.value = String(parte1.idMateria);
            formElements.materiaTexto.value = parte1.materia || "";
            actualizarResumenPromovente();
            formElements.anonima.addEventListener("change", actualizarResumenPromovente);
        } catch (error) {
            mostrarErrorParte1(error.message);
        }
    }

    function actualizarResumenPromovente() {
        formElements.promovente.textContent = formElements.anonima.checked
            ? "Denuncia anónima. El nombre del promovente no se guardará."
            : parte1.promovente.nombreCompleto;
    }

    function mostrarErrorParte1(mensaje) {
        form.querySelectorAll("input, textarea, button").forEach(elemento => {
            elemento.disabled = true;
        });
        formElements.anonima.disabled = true;
        formElements.promovente.className = "text-danger";
        formElements.promovente.textContent =
            `${mensaje} Regrese a Parte 1 y valide nuevamente los datos.`;
    }

    async function inicializarSesion() {
        const respuesta = await fetch("../../api/auth/session", {
            credentials: "same-origin"
        });
        if (respuesta.status === 401) {
            window.location.href = "../../index.html";
            return;
        }
        if (!respuesta.ok) {
            throw new Error("No fue posible verificar la sesión.");
        }
        const sesion = await respuesta.json();
        if (!sesion.ok) {
            throw new Error("No fue posible verificar la sesión.");
        }

        document.getElementById("user-badge").textContent =
            `${sesion.nombreCompleto || "Usuario"} • ${sesion.claveEmpleado || ""}`;
        document.getElementById("user-juzgado").textContent =
            `| ${sesion.nombreJuzgado || "Sin juzgado asignado"}`;
        if (sesion.rol === "ADMIN_OFICIALIA" || sesion.rol === "SUPER_ADMIN") {
            document.getElementById("link-consultar").classList.remove("d-none");
        }
        if (sesion.rol.startsWith("ADMIN_") || sesion.rol === "SUPER_ADMIN") {
            document.getElementById("link-volver-admin").classList.remove("d-none");
        }
    }

    form.addEventListener("submit", event => {
        event.preventDefault();
        if (!parte1 || !form.reportValidity()) return;

        const confirmacion = document.getElementById("confirmacion-contenido");
        confirmacion.replaceChildren();
        const promovente = formElements.anonima.checked
            ? "Denuncia anónima"
            : parte1.promovente.nombreCompleto;
        [
            ["Promovente", promovente],
            ["Parte demandada", formElements.demandado.value.trim()],
            ["Materia", formElements.materiaTexto.value],
            ["Dirección", formElements.direccion.value.trim()],
            ["Descripción de los hechos", formElements.descripcion.value.trim()]
        ].forEach(([etiqueta, valor]) => {
            const bloque = document.createElement("p");
            const titulo = document.createElement("strong");
            titulo.textContent = `${etiqueta}: `;
            bloque.append(titulo, document.createTextNode(valor));
            confirmacion.appendChild(bloque);
        });
        modalConfirmacion.show();
    });

    btnGuardar.addEventListener("click", guardarExpediente);

    async function guardarExpediente() {
        if (enviando || !parte1 || !form.reportValidity()) return;
        enviando = true;
        btnGuardar.disabled = true;

        const anonima = formElements.anonima.checked;
        const promovente = {
            nombreCompleto: anonima ? "" : parte1.promovente.nombreCompleto.trim().toUpperCase(),
            curpRfc: anonima ? "" : (parte1.promovente.curpRfc || "").trim().toUpperCase(),
            tipoPersona: parte1.promovente.tipoPersona,
            rolParte: "DEMANDANTE"
        };
        const partes = {
            idMateria: Number(parte1.idMateria),
            denunciaAnonima: anonima,
            promovente,
            demandado: {
                nombreCompleto: formElements.demandado.value.trim().toUpperCase(),
                curpRfc: formElements.rfcDemandado.value.trim().toUpperCase(),
                rolParte: "DEMANDADO"
            }
        };
        const parametros = new URLSearchParams({
            idMateria: String(parte1.idMateria),
            direccion: formElements.direccion.value.trim(),
            descripcion: formElements.descripcion.value.trim(),
            partesJson: JSON.stringify(partes)
        });

        try {
            const respuesta = await fetch(API, {
                method: "POST",
                credentials: "same-origin",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                    Accept: "application/json"
                },
                body: parametros.toString()
            });
            const resultado = await respuesta.json();
            if (!respuesta.ok || !resultado.ok) {
                throw new Error(resultado.mensaje || resultado.msg ||
                    `No fue posible registrar el oficio (HTTP ${respuesta.status}).`);
            }

            modalConfirmacion.hide();
            document.getElementById("folio-generado").textContent = resultado.folio;
            sessionStorage.removeItem("oficialia_parte1_validada");
            modalExito.show();
        } catch (error) {
            mostrarError(error.message);
        } finally {
            enviando = false;
            btnGuardar.disabled = false;
        }
    }

    function mostrarError(mensaje) {
        window.alert(mensaje);
    }
});
