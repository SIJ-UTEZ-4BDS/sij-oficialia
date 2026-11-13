"use strict";

document.addEventListener("DOMContentLoaded", () => {
    const contexto = new URL("../../", window.location.href);
    const api = ruta => new URL(ruta, contexto).toString();
    const aviso = document.getElementById("mensaje-historial");
    const tablaExpedientes =
        document.getElementById("tabla-historial-expedientes");
    const tablaDuplicidades =
        document.getElementById("tabla-historial-duplicidades");
    const tablaAuditoria =
        document.getElementById("tabla-auditoria-expedientes");
    const filtro = document.getElementById("buscar-historial");
    const modalDetalle = new bootstrap.Modal(
        document.getElementById("modal-detalle-expediente"));
    const contenidoDetalle =
        document.getElementById("contenido-detalle-expediente");
    const modalRestauracion = new bootstrap.Modal(
        document.getElementById("modal-confirmar-restauracion"));
    let expedienteRestaurando = null;
    let expedientes = [];

    filtro.addEventListener("input", renderizarExpedientes);
    document.getElementById("btn-confirmar-restauracion")
        .addEventListener("click", restaurarExpediente);
    inicializar().catch(error => mostrarMensaje("danger", error.message));

    async function inicializar() {
        const respuesta = await fetch(api("api/auth/session"), {
            credentials: "same-origin"
        });
        if (respuesta.status === 401) {
            window.location.href = api("index.html");
            return;
        }
        if (!respuesta.ok) {
            throw new Error("No fue posible verificar la sesión.");
        }

        const sesion = await respuesta.json();
        if (!sesion.ok || sesion.rol !== "ADMIN_OFICIALIA") {
            mostrarMensaje(
                "danger",
                "El historial está disponible sólo para el administrador de Oficialía."
            );
            window.setTimeout(() => {
                window.location.replace("index.html");
            }, 1200);
            return;
        }

        document.getElementById("user-badge").textContent =
            `${sesion.nombreCompleto || "Usuario"} • ${sesion.claveEmpleado || ""}`;
        document.getElementById("user-juzgado").textContent =
            `| ${sesion.nombreJuzgado || "Sin juzgado asignado"}`;
        document.getElementById("link-volver-admin")
            .classList.remove("d-none");

        await cargarHistorial();
        await cargarAuditoria();
        limpiarMensaje();
    }

    async function cargarHistorial() {
        const resultado = await fetch(
            api("api/oficialia/mesas-oficio?accion=historial"),
            {
                credentials: "same-origin",
                headers: { Accept: "application/json" }
            }
        );
        const datos = await resultado.json();
        if (!resultado.ok || !datos.ok) {
            throw new Error(datos.mensaje ||
                `No fue posible cargar el historial (HTTP ${resultado.status}).`);
        }

        expedientes = datos.expedientes || [];
        renderizarExpedientes();
        renderizarDuplicidades(datos.intentosDuplicidad || []);
    }

    async function cargarAuditoria() {
        const respuestaAuditoria = await fetch(
            api("api/oficialia/mesas-oficio?accion=auditoria"),
            {
                credentials: "same-origin",
                headers: { Accept: "application/json" }
            }
        );
        const datosAuditoria = await respuestaAuditoria.json();
        if (!respuestaAuditoria.ok || !datosAuditoria.ok) {
            throw new Error(datosAuditoria.mensaje ||
                `No fue posible cargar la auditoría (HTTP ${respuestaAuditoria.status}).`);
        }
        renderizarAuditoria(datosAuditoria.eventos || []);
    }

    function renderizarExpedientes() {
        const termino = filtro.value.trim().toLocaleLowerCase("es");
        const encontrados = expedientes.filter(expediente =>
            [
                expediente.folio,
                expediente.materia,
                nombrePartes(expediente.partesJson, expediente.fuente),
                expediente.direccion,
                expediente.descripcion,
                expediente.fecha,
                expediente.activo === false ? "Archivado" : expediente.estatus
            ].some(valor => String(valor || "")
                .toLocaleLowerCase("es").includes(termino))
        );
        tablaExpedientes.replaceChildren();

        if (encontrados.length === 0) {
            const fila = document.createElement("tr");
            const celda = document.createElement("td");
            celda.colSpan = 11;
            celda.className = "text-center text-muted py-4";
            celda.textContent = expedientes.length
                ? "No hay expedientes que coincidan con el filtro."
                : "No hay expedientes registrados en este juzgado.";
            fila.appendChild(celda);
            tablaExpedientes.appendChild(fila);
            return;
        }

        encontrados.forEach(expediente => {
            const fila = document.createElement("tr");
            const archivado = expediente.activo === false;
            if (archivado) {
                fila.classList.add("historial-expediente-archivado");
                fila.title = "Expediente archivado; el registro se conserva.";
            }
            const cantidad = Number(expediente.cantidadSimilitudes) || 0;
            if (!archivado && cantidad >= 3) {
                fila.classList.add("consulta-similitud-alta");
                fila.title = `${cantidad} criterios de similitud aceptados`;
            } else if (!archivado && cantidad === 2) {
                fila.classList.add("consulta-similitud-media");
                fila.title = "2 criterios de similitud aceptados";
            } else if (!archivado && cantidad === 1) {
                fila.classList.add("consulta-similitud-baja");
                fila.title = "1 criterio de similitud aceptado";
            }
            const partes = leerPartes(
                expediente.partesJson, expediente.fuente);
            [
                expediente.folio,
                expediente.materia,
                partes.promovente,
                partes.demandado,
                expediente.direccion,
                expediente.descripcion,
                expediente.fecha,
                archivado ? "Archivado" : expediente.estatus,
                cantidad > 0 ? `${cantidad} criterio(s)` : "—"
            ].forEach(valor => {
                const celda = document.createElement("td");
                celda.textContent = valor == null ? "" : String(valor);
                fila.appendChild(celda);
            });
            const celdaDetalle = document.createElement("td");
            const botonDetalle = document.createElement("button");
            botonDetalle.type = "button";
            botonDetalle.className = "btn btn-sm btn-outline-secondary";
            botonDetalle.setAttribute("aria-label",
                `Ver detalle del expediente ${expediente.folio || ""}`);
            botonDetalle.title = "Ver toda la información";
            const icono = document.createElement("i");
            icono.className = "bi bi-eye";
            botonDetalle.appendChild(icono);
            botonDetalle.addEventListener("click", () =>
                mostrarDetalle(expediente));
            celdaDetalle.appendChild(botonDetalle);
            fila.appendChild(celdaDetalle);

            const celdaAcciones = document.createElement("td");
            if (archivado) {
                const botonRestaurar = document.createElement("button");
                botonRestaurar.type = "button";
                botonRestaurar.className =
                    "btn btn-sm btn-outline-success";
                botonRestaurar.title = "Restaurar expediente";
                botonRestaurar.setAttribute("aria-label",
                    `Restaurar expediente ${expediente.folio || ""}`);
                const iconoRestaurar = document.createElement("i");
                iconoRestaurar.className = "bi bi-arrow-counterclockwise me-1";
                botonRestaurar.append(
                    iconoRestaurar, document.createTextNode("Restaurar"));
                botonRestaurar.addEventListener("click", () => {
                    expedienteRestaurando = expediente;
                    document.getElementById(
                        "mensaje-confirmar-restauracion").textContent =
                        `Se restaurará el expediente ${expediente.folio || ""}. ` +
                        "Volverá a estar activo en la consulta del juzgado; " +
                        "su registro histórico se conservará. ¿Desea continuar?";
                    modalRestauracion.show();
                });
                celdaAcciones.appendChild(botonRestaurar);
            } else {
                celdaAcciones.textContent = "—";
            }
            fila.appendChild(celdaAcciones);
            tablaExpedientes.appendChild(fila);
        });
    }

    async function restaurarExpediente() {
        if (!expedienteRestaurando || expedienteRestaurando.activo !== false) {
            return;
        }
        const esLegado = expedienteRestaurando.fuente === "LEGADO";
        const idExpediente = esLegado
            ? expedienteRestaurando.idExpediente
            : expedienteRestaurando.idRegistro;
        if (!idExpediente || !expedienteRestaurando.fuente) {
            mostrarMensaje("danger",
                "No se pudo identificar el expediente para restaurarlo.");
            return;
        }

        const boton = document.getElementById(
            "btn-confirmar-restauracion");
        boton.disabled = true;
        const parametros = new URLSearchParams({
            accion: "desarchivar",
            fuente: expedienteRestaurando.fuente,
            idRegistro: String(idExpediente)
        });
        try {
            const respuesta = await fetch(
                api("api/oficialia/mesas-oficio"), {
                    method: "POST",
                    credentials: "same-origin",
                    headers: {
                        Accept: "application/json",
                        "Content-Type":
                            "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: parametros
                });
            await leerRespuesta(respuesta);
            expedienteRestaurando.activo = true;
            modalRestauracion.hide();
            renderizarExpedientes();
            mostrarMensaje("success",
                "Expediente restaurado. El registro histórico se conservó.");
            expedienteRestaurando = null;
            try {
                await cargarAuditoria();
            } catch (error) {
                mostrarMensaje("warning",
                    `El expediente se restauró, pero no se pudo actualizar la auditoría: ${error.message}`);
            }
        } catch (error) {
            mostrarMensaje("danger", error.message);
        } finally {
            boton.disabled = false;
        }
    }

    async function leerRespuesta(respuesta) {
        const cuerpo = await respuesta.text();
        let datos;
        try {
            datos = JSON.parse(cuerpo);
        } catch (error) {
            throw new Error(
                `Respuesta inválida del servidor (HTTP ${respuesta.status}).`);
        }
        if (!respuesta.ok || !datos.ok) {
            throw new Error(datos.mensaje || `Error HTTP ${respuesta.status}.`);
        }
        return datos;
    }

    function renderizarDuplicidades(eventos) {
        tablaDuplicidades.replaceChildren();
        if (eventos.length === 0) {
            const fila = document.createElement("tr");
            const celda = document.createElement("td");
            celda.colSpan = 7;
            celda.className = "text-center text-muted py-4";
            celda.textContent = "No hay intentos de duplicidad registrados.";
            fila.appendChild(celda);
            tablaDuplicidades.appendChild(fila);
            return;
        }

        eventos.forEach(evento => {
            const fila = document.createElement("tr");
            [
                evento.fecha,
                etiquetaAccion(evento.accion),
                evento.responsable,
                evento.cantidadCoincidencias,
                evento.criterios,
                evento.folioRegistrado
            ].forEach(valor => {
                const celda = document.createElement("td");
                celda.textContent = valor == null || valor === ""
                    ? "—" : String(valor);
                fila.appendChild(celda);
            });
            const celdaDetalle = document.createElement("td");
            const botonDetalle = document.createElement("button");
            botonDetalle.type = "button";
            botonDetalle.className = "btn btn-sm btn-outline-secondary";
            botonDetalle.setAttribute("aria-label", "Ver detalle del evento");
            botonDetalle.title = "Ver toda la información";
            const icono = document.createElement("i");
            icono.className = "bi bi-eye";
            botonDetalle.appendChild(icono);
            botonDetalle.addEventListener("click", () =>
                mostrarDetalleEvento(evento));
            celdaDetalle.appendChild(botonDetalle);
            fila.appendChild(celdaDetalle);
            tablaDuplicidades.appendChild(fila);
        });
    }

    function leerPartes(valor, fuente) {
        if (!valor) {
            return { promovente: "", demandado: "", datos: {} };
        }

        try {
            const partes = typeof valor === "string"
                ? JSON.parse(valor) : valor;
            if (typeof partes === "string") {
                return {
                    promovente: partes, demandado: "", datos: {}
                };
            }
            return {
                promovente: partes.denunciaAnonima
                    ? "Denuncia anónima"
                    : partes.promovente?.nombreCompleto || "",
                demandado: partes.demandado?.nombreCompleto || "",
                datos: partes
            };
        } catch (error) {
            if (fuente === "LEGADO") {
                return {
                    promovente: String(valor), demandado: "", datos: {}
                };
            }
            console.error("No se pudieron leer las partes del historial.", error);
            return { promovente: "", demandado: "", datos: {} };
        }
    }

    function renderizarAuditoria(eventos) {
        tablaAuditoria.replaceChildren();
        if (eventos.length === 0) {
            const fila = document.createElement("tr");
            const celda = document.createElement("td");
            celda.colSpan = 6;
            celda.className = "text-center text-muted py-4";
            celda.textContent = "No hay cambios de expedientes en este juzgado.";
            fila.appendChild(celda);
            tablaAuditoria.appendChild(fila);
            return;
        }
        eventos.forEach(evento => {
            const fila = document.createElement("tr");
            [
                evento.fecha,
                evento.responsable,
                evento.folio,
                evento.campo,
                evento.valorAnterior,
                evento.valorNuevo
            ].forEach(valor => {
                const celda = document.createElement("td");
                celda.textContent = valor == null || valor === ""
                    ? "—" : String(valor);
                fila.appendChild(celda);
            });
            tablaAuditoria.appendChild(fila);
        });
    }

    function mostrarDetalle(expediente) {
        document.getElementById("titulo-detalle-expediente").textContent =
            "Detalle completo del expediente";
        const partes = leerPartes(
            expediente.partesJson, expediente.fuente).datos;
        const promovente = partes.promovente || {};
        const demandado = partes.demandado || {};
        const contacto = promovente.contacto || {};
        const anonima = Boolean(partes.denunciaAnonima);
        const campos = [
            ["Folio", expediente.folio],
            ["Materia", expediente.materia],
            ["Fecha de registro", expediente.fecha],
            ["Estado", expediente.activo === false
                ? "Archivado" : expediente.estatus],
            ["Promovente", anonima
                ? "Denuncia anónima" : promovente.nombreCompleto],
            ["RFC/CURP del promovente",
                anonima ? "" : promovente.curpRfc],
            ["Tipo de persona", promovente.tipoPersona],
            ["Teléfono de contacto", contacto.telefono],
            ["Correo electrónico", contacto.correoElectronico],
            ["Demandado", demandado.nombreCompleto],
            ["RFC/CURP del demandado", demandado.curpRfc],
            ["Dirección", expediente.direccion],
            ["Descripción de los hechos", expediente.descripcion],
            ["Similitudes admitidas", expediente.cantidadSimilitudes || "Ninguna"]
        ];
        const direccion = partes.direccionComponentes;
        if (direccion && typeof direccion === "object") {
            campos.splice(12, 0,
                ["Calle", direccion.calle],
                ["Número exterior", direccion.numeroExterior],
                ["Número interior", direccion.numeroInterior],
                ["Colonia", direccion.colonia],
                ["Municipio", direccion.municipio],
                ["Estado", direccion.estado],
                ["Código postal", direccion.codigoPostal]);
        }
        contenidoDetalle.replaceChildren();
        campos.forEach(([etiqueta, valor]) => {
            const fila = document.createElement("p");
            fila.className = "mb-2";
            const titulo = document.createElement("strong");
            titulo.textContent = `${etiqueta}: `;
            fila.append(titulo, document.createTextNode(
                valor == null || valor === "" ? "—" : String(valor)));
            contenidoDetalle.appendChild(fila);
        });
        modalDetalle.show();
    }

    function mostrarDetalleEvento(evento) {
        document.getElementById("titulo-detalle-expediente").textContent =
            "Detalle del evento de duplicidad";
        const campos = [
            ["Fecha", evento.fecha],
            ["Decisión", etiquetaAccion(evento.accion)],
            ["Responsable", evento.responsable],
            ["Coincidencias encontradas", evento.cantidadCoincidencias],
            ["Cantidad de criterios de similitud",
                evento.cantidadSimilitudes],
            ["Criterios detectados", evento.criterios],
            ["Folio registrado", evento.folioRegistrado]
        ];
        contenidoDetalle.replaceChildren();
        campos.forEach(([etiqueta, valor]) => {
            const fila = document.createElement("p");
            fila.className = "mb-2";
            const titulo = document.createElement("strong");
            titulo.textContent = `${etiqueta}: `;
            fila.append(titulo, document.createTextNode(
                valor == null || valor === "" ? "—" : String(valor)));
            contenidoDetalle.appendChild(fila);
        });
        modalDetalle.show();
    }

    function nombrePartes(valor, fuente) {
        const partes = leerPartes(valor, fuente);
        return `${partes.promovente} ${partes.demandado}`;
    }

    function etiquetaAccion(accion) {
        const etiquetas = {
            DUPLICIDAD_CANCELADA: "Canceló el trámite",
            DUPLICIDAD_CONTINUAR: "Continuó con el trámite",
            DUPLICIDAD_ADMITIDA: "Expediente duplicado admitido"
        };
        return etiquetas[accion] || accion || "";
    }

    function mostrarMensaje(tipo, texto) {
        aviso.className = `alert alert-${tipo}`;
        aviso.textContent = texto;
    }

    function limpiarMensaje() {
        aviso.className = "alert d-none";
        aviso.textContent = "";
    }
});
