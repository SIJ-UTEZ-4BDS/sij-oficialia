"use strict";

document.addEventListener("DOMContentLoaded", () => {
    const form = document.getElementById("form-parte1");
    const nombre = document.getElementById("input-nombre-parte");
    const curpRfc = document.getElementById("input-rfc-parte");
    const demandado = document.getElementById("input-nombre-demandado");
    const rfcDemandado = document.getElementById("input-rfc-demandado");
    const materia = document.getElementById("select-materia");
    const tipoPersona = document.getElementById("select-tipo-persona");
    const btnSiguiente = document.getElementById("btn-siguiente");
    const aviso = document.getElementById("mensaje-registro");
    const validaciones = window.OficialiaValidation;
    const modalElemento = document.getElementById("modal-duplicidad");
    const modalDuplicidad = new bootstrap.Modal(modalElemento, {
        backdrop: "static",
        keyboard: false
    });
    const contexto = new URL("../../", window.location.href);
    const api = ruta => new URL(ruta, contexto).toString();

    let idJuzgado = null;
    let timer = null;
    let requestId = 0;
    let consultaActual = "";
    let verificacionCompleta = false;
    let duplicado = false;
    let duplicidadAceptada = false;
    let coincidenciasActuales = [];
    const camposTocados = new Set();

    inicializar().catch(error => mostrarError(error.message));

    async function inicializar() {
        const [sesionResponse, materiasResponse] = await Promise.all([
            fetch(api("api/auth/session"), { credentials: "same-origin" }),
            fetch(api("api/oficialia/mesas-oficio?accion=materias"), {
                credentials: "same-origin",
                headers: { Accept: "application/json" }
            })
        ]);

        if (sesionResponse.status === 401) {
            window.location.href = api("index.html");
            return;
        }
        if (!sesionResponse.ok) {
            throw new Error("No fue posible verificar la sesión.");
        }
        if (!materiasResponse.ok) {
            throw new Error("No fue posible cargar las materias.");
        }

        const sesion = await sesionResponse.json();
        const datosMaterias = await materiasResponse.json();
        if (!sesion.ok || !datosMaterias.ok) {
            throw new Error(datosMaterias.mensaje ||
                "No fue posible inicializar el registro.");
        }
        if (sesion.rol !== "SUPER_ADMIN" &&
            !String(sesion.rol || "").endsWith("_OFICIALIA")) {
            window.location.href = api("index.html");
            return;
        }

        idJuzgado = Number(sesion.idJuzgado);
        document.getElementById("user-badge").textContent =
            `${sesion.nombreCompleto || "Usuario"} • ${sesion.claveEmpleado || ""}`;
        document.getElementById("user-juzgado").textContent =
            `| ${sesion.nombreJuzgado || "Sin juzgado asignado"}`;

        if (sesion.rol === "ADMIN_OFICIALIA" || sesion.rol === "SUPER_ADMIN") {
            document.getElementById("link-consultar").classList.remove("d-none");
        }
        if (sesion.rol === "ADMIN_OFICIALIA") {
            document.getElementById("link-historial").classList.remove("d-none");
        }
        if (sesion.rol !== "OPERADOR_OFICIALIA") {
            document.getElementById("btn-detalle-duplicidad")
                .classList.add("d-none");
        }
        if (sesion.rol.startsWith("ADMIN_") || sesion.rol === "SUPER_ADMIN") {
            document.getElementById("link-volver-admin").classList.remove("d-none");
        }

        const opciones = datosMaterias.materias || [];
        materia.replaceChildren(new Option("Selecciona una materia", ""));
        opciones.forEach(item => {
            materia.add(new Option(item.nombreMateria, item.idMateria));
        });
        if (opciones.length === 0) {
            throw new Error("No hay materias activas para registrar.");
        }

        form.addEventListener("input", event => {
            camposTocados.add(event.target);
            validarCampos();
            limpiarMensaje();
            programarVerificacion();
        });
        form.addEventListener("change", event => {
            camposTocados.add(event.target);
            validarCampos();
            limpiarMensaje();
            programarVerificacion();
        });
        form.addEventListener("focusout", event => {
            camposTocados.add(event.target);
            validarCampos();
        });
        [curpRfc, rfcDemandado].forEach(input => {
            input.addEventListener("input", () => {
                input.value = input.value.toUpperCase().trim();
            });
        });
        btnSiguiente.addEventListener("click", continuar);
        document.getElementById("btn-continuar-duplicidad")
            .addEventListener("click", () => resolverDuplicidad("CONTINUAR"));
        document.getElementById("btn-cancelar-duplicidad")
            .addEventListener("click", () => resolverDuplicidad("CANCELAR"));
        document.getElementById("btn-detalle-duplicidad")
            .addEventListener("click", verDetalles);

        const anterior = sessionStorage.getItem("oficialia_parte1_validada");
        if (anterior) {
            try {
                const datos = JSON.parse(anterior);
                if (!datos.demandado?.nombreCompleto) {
                    throw new Error("Faltan datos de la parte demandada.");
                }
                nombre.value = datos.promovente?.nombreCompleto || "";
                curpRfc.value = datos.promovente?.curpRfc || "";
                tipoPersona.value = datos.promovente?.tipoPersona || "FISICA";
                demandado.value = datos.demandado.nombreCompleto;
                rfcDemandado.value = datos.demandado.curpRfc || "";
                materia.value = datos.idMateria || "";
                programarVerificacion();
            } catch (error) {
                console.error("No se pudieron recuperar los datos de Parte 1.", error);
                sessionStorage.removeItem("oficialia_parte1_validada");
            }
        }
    }

    function programarVerificacion() {
        clearTimeout(timer);
        requestId++;
        verificacionCompleta = false;
        duplicado = false;
        duplicidadAceptada = false;
        coincidenciasActuales = [];
        btnSiguiente.disabled = true;

        if (!validarCampos() ||
            nombre.value.trim().length < 2 ||
            demandado.value.trim().length < 2 ||
            !materia.value) {
            return;
        }
        timer = setTimeout(buscarCoincidencias, 350);
    }

    function validarCampos(forzar = false) {
        let valido = true;
        const reglas = [
            [materia, materia.value ? "" : "Selecciona una materia."],
            [tipoPersona, tipoPersona.value ? "" : "Selecciona el tipo de persona."],
            [nombre, nombre.value.trim().length >= 2
                ? "" : "Escribe el nombre o razón social del promovente."],
            [curpRfc, validaciones.validarIdentificador(curpRfc.value).mensaje || ""],
            [demandado, demandado.value.trim().length >= 2
                ? "" : "Escribe el nombre o razón social de la parte demandada."],
            [rfcDemandado,
                validaciones.validarIdentificador(rfcDemandado.value).mensaje || ""]
        ];
        reglas.forEach(([elemento, error]) => {
            const mostrar = forzar || camposTocados.has(elemento) ||
                Boolean(elemento.value.trim());
            if (error) {
                valido = false;
                if (mostrar) validaciones.marcarInvalido(elemento, error);
            } else if (mostrar) {
                validaciones.marcarValido(elemento);
            } else {
                validaciones.limpiarEstado(elemento);
            }
        });
        return valido;
    }

    async function buscarCoincidencias() {
        const consulta = ++requestId;
        const datos = leerDatos();
        consultaActual = JSON.stringify(datos);

        if (!Number.isInteger(idJuzgado) || idJuzgado <= 0) {
            mostrarError("Su cuenta no tiene un juzgado asignado.");
            return;
        }

        try {
            const result = await enviar("buscar", datos);
            if (consulta !== requestId ||
                JSON.stringify(leerDatos()) !== consultaActual) {
                return;
            }

            coincidenciasActuales = Array.isArray(result.coincidencias)
                ? result.coincidencias : [];
            duplicado = coincidenciasActuales.length > 0;
            verificacionCompleta = true;
            if (duplicado) {
                renderizarCoincidencias(coincidenciasActuales);
                document.getElementById("resumen-duplicidad").textContent =
                    `Se detectaron ${coincidenciasActuales.length} posibles coincidencias. Revise las señales antes de decidir si continúa.`;
                modalDuplicidad.show();
            } else {
                btnSiguiente.disabled = !validarCampos();
            }
        } catch (error) {
            if (consulta !== requestId) return;
            verificacionCompleta = false;
            btnSiguiente.disabled = true;
            mostrarError(`No fue posible verificar coincidencias: ${error.message}`);
        }
    }

    async function enviar(accion, datos, adicionales = {}) {
        const body = new URLSearchParams({
            ...datos,
            accion,
            ...adicionales
        });
        const response = await fetch(api("api/oficialia/duplicidad"), {
            method: "POST",
            credentials: "same-origin",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                Accept: "application/json"
            },
            body: body.toString()
        });
        const result = await response.json();
        if (!response.ok || !result.ok) {
            throw new Error(result.mensaje || result.error ||
                `Error HTTP ${response.status}`);
        }
        return result;
    }

    async function resolverDuplicidad(decision) {
        const botones = [
            document.getElementById("btn-continuar-duplicidad"),
            document.getElementById("btn-cancelar-duplicidad")
        ];
        botones.forEach(boton => boton.disabled = true);
        try {
            await enviar("decision", leerDatos(), { decision });
            duplicidadAceptada = decision === "CONTINUAR";
            modalDuplicidad.hide();
            btnSiguiente.disabled = !duplicidadAceptada ||
                !verificacionCompleta || !validarCampos();
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
            const result = await enviar("detalle", leerDatos());
            renderizarDetalles(result.detalles || []);
            panel.classList.remove("d-none");
        } catch (error) {
            mostrarError(`No fue posible consultar el detalle: ${error.message}`);
        } finally {
            boton.disabled = false;
            boton.innerHTML = '<i class="bi bi-card-list me-1"></i>Ver toda la información';
        }
    }

    function renderizarCoincidencias(coincidencias) {
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
                ["Juzgado / distrito", `${detalle.juzgado || ""} / ${detalle.distrito || ""}`],
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
            tarjeta.setAttribute("aria-label", `Detalle de coincidencia ${indice + 1}`);
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

    function leerDatos() {
        return {
            nombrePromovente: nombre.value.trim(),
            curpRfcPromovente: curpRfc.value.trim().toUpperCase(),
            nombreDemandado: demandado.value.trim(),
            curpRfcDemandado: rfcDemandado.value.trim().toUpperCase(),
            idMateria: materia.value,
            descripcion: ""
        };
    }

    function continuar() {
        if (!verificacionCompleta ||
            (duplicado && !duplicidadAceptada) ||
            !validarCampos(true)) {
            return;
        }

        sessionStorage.setItem("oficialia_parte1_validada", JSON.stringify({
            idMateria: materia.value,
            materia: materia.selectedOptions[0].textContent,
            promovente: {
                nombreCompleto: nombre.value.trim(),
                curpRfc: curpRfc.value.trim().toUpperCase(),
                tipoPersona: tipoPersona.value,
                rolParte: "DEMANDANTE"
            },
            demandado: {
                nombreCompleto: demandado.value.trim(),
                curpRfc: rfcDemandado.value.trim().toUpperCase(),
                rolParte: "DEMANDADO"
            }
        }));
        window.location.href = "registro-parte2.html";
    }

    function mostrarError(mensaje) {
        aviso.className = "alert alert-danger mt-3";
        aviso.textContent = mensaje;
        btnSiguiente.disabled = true;
    }

    function limpiarMensaje() {
        aviso.className = "alert d-none mt-3";
        aviso.textContent = "";
    }
});
