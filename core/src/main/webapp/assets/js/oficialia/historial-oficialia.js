"use strict";

document.addEventListener("DOMContentLoaded", () => {
    const contexto = new URL("../../", window.location.href);
    const api = ruta => new URL(ruta, contexto).toString();
    const aviso = document.getElementById("mensaje-historial");
    const tablaExpedientes =
        document.getElementById("tabla-historial-expedientes");
    const tablaDuplicidades =
        document.getElementById("tabla-historial-duplicidades");
    const filtro = document.getElementById("buscar-historial");
    let expedientes = [];

    filtro.addEventListener("input", renderizarExpedientes);
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
        limpiarMensaje();
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
                expediente.fecha
            ].some(valor => String(valor || "")
                .toLocaleLowerCase("es").includes(termino))
        );
        tablaExpedientes.replaceChildren();

        if (encontrados.length === 0) {
            const fila = document.createElement("tr");
            const celda = document.createElement("td");
            celda.colSpan = 9;
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
            const cantidad = Number(expediente.cantidadSimilitudes) || 0;
            if (cantidad >= 3) {
                fila.classList.add("consulta-similitud-alta");
                fila.title = `${cantidad} criterios de similitud aceptados`;
            } else if (cantidad === 2) {
                fila.classList.add("consulta-similitud-media");
                fila.title = "2 criterios de similitud aceptados";
            } else if (cantidad === 1) {
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
                expediente.estatus,
                cantidad > 0 ? `${cantidad} criterio(s)` : "—"
            ].forEach(valor => {
                const celda = document.createElement("td");
                celda.textContent = valor == null ? "" : String(valor);
                fila.appendChild(celda);
            });
            tablaExpedientes.appendChild(fila);
        });
    }

    function renderizarDuplicidades(eventos) {
        tablaDuplicidades.replaceChildren();
        if (eventos.length === 0) {
            const fila = document.createElement("tr");
            const celda = document.createElement("td");
            celda.colSpan = 6;
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
            tablaDuplicidades.appendChild(fila);
        });
    }

    function leerPartes(valor, fuente) {
        if (!valor) {
            return { promovente: "", demandado: "" };
        }
        try {
            const partes = typeof valor === "string"
                ? JSON.parse(valor) : valor;
            if (typeof partes === "string") {
                return { promovente: partes, demandado: "" };
            }
            return {
                promovente: partes.denunciaAnonima
                    ? "Denuncia anónima"
                    : partes.promovente?.nombreCompleto || "",
                demandado: partes.demandado?.nombreCompleto || ""
            };
        } catch (error) {
            if (fuente === "LEGADO") {
                return { promovente: String(valor), demandado: "" };
            }
            console.error("No se pudieron leer las partes del historial.", error);
            return { promovente: "", demandado: "" };
        }
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
