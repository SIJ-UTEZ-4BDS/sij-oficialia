"use strict";

document.addEventListener("DOMContentLoaded", () => {
    const API = "../../api/oficialia/mesas-oficio";
    const API_DUPLICIDAD = "../../api/oficialia/duplicidad";
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
    const modalExito = new bootstrap.Modal(
        document.getElementById("modal-exito")
    );
    const modalDuplicidad = new bootstrap.Modal(
        document.getElementById("modal-duplicidad"),
        { backdrop: "static", keyboard: false }
    );
    const datosValidados = sessionStorage.getItem("oficialia_parte1_validada");

    let parte1 = null;
    let enviando = false;
    let aceptarDuplicidad = false;
    let reintentarGuardado = false;

    recuperarParte1();
    inicializarSesion().catch(error => mostrarError(error.message));

    document.getElementById("btn-detalle-duplicidad")
        .addEventListener("click", verDetalles);
    document.getElementById("btn-continuar-duplicidad")
        .addEventListener("click", () => resolverDuplicidad("CONTINUAR"));
    document.getElementById("btn-cancelar-duplicidad")
        .addEventListener("click", () => resolverDuplicidad("CANCELAR"));

    function recuperarParte1() {
        if (!datosValidados) {
            mostrarErrorParte1("No se encontraron datos validados de la Parte 1.");
            return;
        }
        try {
            parte1 = JSON.parse(datosValidados);
            if (!parte1 || !parte1.promovente || !parte1.idMateria ||
                !parte1.promovente.nombreCompleto ||
                !parte1.demandado?.nombreCompleto) {
                throw new Error(
                    "Los datos validados de la Parte 1 están incompletos.");
            }

            formElements.materia.value = String(parte1.idMateria);
            formElements.materiaTexto.value = parte1.materia || "";
            formElements.demandado.value = parte1.demandado.nombreCompleto;
            formElements.rfcDemandado.value =
                parte1.demandado.curpRfc || "";
            formElements.demandado.readOnly = true;
            formElements.rfcDemandado.readOnly = true;
            actualizarResumenPromovente();
            formElements.anonima.addEventListener(
                "change", actualizarResumenPromovente);
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
        if (sesion.rol === "ADMIN_OFICIALIA" ||
            sesion.rol === "SUPER_ADMIN") {
            document.getElementById("link-consultar")
                .classList.remove("d-none");
        }
        if (sesion.rol === "ADMIN_OFICIALIA") {
            document.getElementById("link-historial")
                .classList.remove("d-none");
        }
        if (sesion.rol !== "OPERADOR_OFICIALIA") {
            document.getElementById("btn-detalle-duplicidad")
                .classList.add("d-none");
        }
        if (sesion.rol.startsWith("ADMIN_") ||
            sesion.rol === "SUPER_ADMIN") {
            document.getElementById("link-volver-admin")
                .classList.remove("d-none");
        }
    }

    form.addEventListener("submit", event => {
        event.preventDefault();
        if (!parte1 || !form.reportValidity()) return;
        verificarAntesDeConfirmar().catch(error => mostrarError(error.message));
    });

    async function verificarAntesDeConfirmar() {
        aceptarDuplicidad = false;
        const result = await enviarDuplicidad("buscar");
        if (Array.isArray(result.coincidencias) &&
            result.coincidencias.length > 0) {
            mostrarCoincidencias(result.coincidencias);
            document.getElementById("resumen-duplicidad").textContent =
                `Se detectaron ${result.coincidencias.length} posibles coincidencias. Revise las señales antes de decidir si continúa.`;
            reintentarGuardado = false;
            modalDuplicidad.show();
            return;
        }
        abrirConfirmacion();
    }

    function abrirConfirmacion() {
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
    }

    btnGuardar.addEventListener("click", guardarExpediente);

    async function guardarExpediente() {
        if (enviando || !parte1 || !form.reportValidity()) return;
        enviando = true;
        btnGuardar.disabled = true;

        const anonima = formElements.anonima.checked;
        const partes = {
            idMateria: Number(parte1.idMateria),
            denunciaAnonima: anonima,
            promovente: {
                nombreCompleto: anonima
                    ? "" : parte1.promovente.nombreCompleto.trim().toUpperCase(),
                curpRfc: anonima
                    ? "" : (parte1.promovente.curpRfc || "")
                        .trim().toUpperCase(),
                tipoPersona: parte1.promovente.tipoPersona,
                rolParte: "DEMANDANTE"
            },
            demandado: {
                nombreCompleto: formElements.demandado.value
                    .trim().toUpperCase(),
                curpRfc: formElements.rfcDemandado.value
                    .trim().toUpperCase(),
                rolParte: "DEMANDADO"
            }
        };
        const parametros = new URLSearchParams({
            idMateria: String(parte1.idMateria),
            direccion: formElements.direccion.value.trim(),
            descripcion: formElements.descripcion.value.trim(),
            partesJson: JSON.stringify(partes),
            nombrePromoventeBusqueda:
                parte1.promovente.nombreCompleto || "",
            curpRfcPromoventeBusqueda:
                parte1.promovente.curpRfc || "",
            aceptarDuplicidad: String(aceptarDuplicidad)
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
            if (respuesta.status === 409 && resultado.duplicado) {
                aceptarDuplicidad = false;
                reintentarGuardado = true;
                mostrarCoincidencias(resultado.coincidencias || []);
                document.getElementById("resumen-duplicidad").textContent =
                    `La verificación final detectó ${resultado.coincidencias?.length || 0} posibles coincidencias. Revise y decida si desea continuar.`;
                document.getElementById("modal-confirmacion")
                    .addEventListener(
                        "hidden.bs.modal",
                        () => modalDuplicidad.show(),
                        { once: true }
                    );
                modalConfirmacion.hide();
                return;
            }
            if (!respuesta.ok || !resultado.ok) {
                throw new Error(resultado.mensaje || resultado.msg ||
                    `No fue posible registrar el oficio (HTTP ${respuesta.status}).`);
            }

            modalConfirmacion.hide();
            document.getElementById("folio-generado").textContent =
                resultado.folio;
            sessionStorage.removeItem("oficialia_parte1_validada");
            modalExito.show();
        } catch (error) {
            mostrarError(error.message);
        } finally {
            enviando = false;
            btnGuardar.disabled = false;
        }
    }

    async function enviarDuplicidad(accion, adicionales = {}) {
        const payload = {
            nombrePromovente: parte1.promovente.nombreCompleto || "",
            curpRfcPromovente: parte1.promovente.curpRfc || "",
            nombreDemandado: formElements.demandado.value.trim(),
            curpRfcDemandado: formElements.rfcDemandado.value.trim(),
            idMateria: String(parte1.idMateria),
            descripcion: formElements.descripcion.value.trim(),
            accion,
            ...adicionales
        };
        const respuesta = await fetch(API_DUPLICIDAD, {
            method: "POST",
            credentials: "same-origin",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                Accept: "application/json"
            },
            body: new URLSearchParams(payload).toString()
        });
        const resultado = await respuesta.json();
        if (!respuesta.ok || !resultado.ok) {
            throw new Error(resultado.mensaje ||
                `No fue posible verificar coincidencias (HTTP ${respuesta.status}).`);
        }
        return resultado;
    }

    async function resolverDuplicidad(decision) {
        const botones = [
            document.getElementById("btn-continuar-duplicidad"),
            document.getElementById("btn-cancelar-duplicidad")
        ];
        botones.forEach(boton => boton.disabled = true);
        try {
            await enviarDuplicidad("decision", { decision });
            aceptarDuplicidad = decision === "CONTINUAR";
            modalDuplicidad.hide();
            if (aceptarDuplicidad && reintentarGuardado) {
                reintentarGuardado = false;
                await guardarExpediente();
            } else if (aceptarDuplicidad) {
                abrirConfirmacion();
            }
        } catch (error) {
            mostrarError(`No se pudo registrar la decisión: ${error.message}`);
        } finally {
            botones.forEach(boton => boton.disabled = false);
        }
    }

    async function verDetalles() {
        const boton = document.getElementById("btn-detalle-duplicidad");
        const panel = document.getElementById("detalle-duplicidad");
        boton.disabled = true;
        boton.textContent = "Cargando información...";
        try {
            const result = await enviarDuplicidad("detalle");
            renderizarDetalles(result.detalles || []);
            panel.classList.remove("d-none");
        } catch (error) {
            mostrarError(`No fue posible consultar el detalle: ${error.message}`);
        } finally {
            boton.disabled = false;
            boton.innerHTML =
                '<i class="bi bi-card-list me-1"></i>Ver toda la información';
        }
    }

    function mostrarCoincidencias(coincidencias) {
        const tabla = document.getElementById("tabla-duplicidad");
        tabla.replaceChildren();
        coincidencias.forEach(coincidencia => {
            const fila = document.createElement("tr");
            [
                coincidencia.folio,
                coincidencia.juzgado,
                coincidencia.distrito,
                coincidencia.materia,
                etiquetasSimilitud(coincidencia.similitudes)
            ].forEach(valor => {
                const celda = document.createElement("td");
                celda.textContent = valor == null ? "" : String(valor);
                fila.appendChild(celda);
            });
            tabla.appendChild(fila);
        });
        document.getElementById("detalle-duplicidad").replaceChildren();
        document.getElementById("detalle-duplicidad")
            .classList.add("d-none");
    }

    function renderizarDetalles(detalles) {
        const panel = document.getElementById("detalle-duplicidad");
        panel.replaceChildren();
        detalles.forEach((detalle, indice) => {
            const tarjeta = document.createElement("article");
            tarjeta.className = "border rounded p-3 mb-3";
            const encabezado = document.createElement("h3");
            encabezado.className = "h6 fw-bold";
            encabezado.textContent =
                `${detalle.folio || "Sin folio"} • ${detalle.materia || ""}`;
            tarjeta.appendChild(encabezado);
            [
                ["Juzgado / distrito",
                    `${detalle.juzgado || ""} / ${detalle.distrito || ""}`],
                ["Fecha", detalle.fecha],
                ["Partes", textoPartes(detalle.partes)],
                ["Fuente", detalle.fuente],
                ["Dirección", detalle.direccion],
                ["Descripción", detalle.descripcion],
                ["Estado", detalle.estatus],
                ["Similitudes", etiquetasSimilitud(detalle.similitudes)]
            ].forEach(([etiqueta, valor]) => {
                const parrafo = document.createElement("p");
                parrafo.className = "mb-1";
                const titulo = document.createElement("strong");
                titulo.textContent = `${etiqueta}: `;
                parrafo.append(titulo, document.createTextNode(valor || "—"));
                tarjeta.appendChild(parrafo);
            });
            tarjeta.setAttribute(
                "aria-label", `Detalle de coincidencia ${indice + 1}`);
            panel.appendChild(tarjeta);
        });
    }

    function textoPartes(partes) {
        if (!partes || typeof partes !== "object") {
            return String(partes || "");
        }
        return [
            parteCompleta("Promovente", partes.denunciaAnonima
                ? null : partes.promovente),
            parteCompleta("Demandado", partes.demandado)
        ].join("; ");
    }

    function parteCompleta(etiqueta, parte) {
        if (!parte || typeof parte !== "object") {
            return `${etiqueta}: Denuncia anónima`;
        }
        return `${etiqueta}: ${parte.nombreCompleto || "—"}`
            + `${parte.curpRfc ? ` (RFC/identificador: ${parte.curpRfc})` : ""}`
            + `${parte.tipoPersona ? `, ${parte.tipoPersona}` : ""}`;
    }

    function etiquetasSimilitud(similitudes) {
        const etiquetas = {
            PROMOVENTE: "Nombre promovente",
            DEMANDADO: "Nombre demandado",
            RFC: "RFC",
            MATERIA: "Materia",
            DESCRIPCION: "Descripción"
        };
        return (Array.isArray(similitudes) ? similitudes : [])
            .map(criterio => etiquetas[criterio] || criterio)
            .join(", ");
    }

    function mostrarError(mensaje) {
        window.alert(mensaje);
    }
});
