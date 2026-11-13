"use strict";

document.addEventListener("DOMContentLoaded", () => {
    const contexto = new URL("../../", window.location.href);
    const api = ruta => new URL(ruta, contexto).toString();
    const tabla = document.getElementById("tabla-expedientes");
    const filtro = document.getElementById("buscar-expedientes");
    const aviso = document.getElementById("mensaje-consulta");
    const conteo = document.getElementById("resultado-conteo");

    let expedientes = [];
    let orden = { campo: "fecha", ascendente: false };

    document.getElementById("btn-actualizar").addEventListener("click", () => {
        cargarExpedientes().catch(error => mostrarMensaje("danger", error.message));
    });
    filtro.addEventListener("input", renderizar);
    document.querySelectorAll("[data-sort]").forEach(boton => {
        boton.addEventListener("click", () => {
            const campo = boton.dataset.sort;
            orden = {
                campo,
                ascendente: orden.campo === campo ? !orden.ascendente : true
            };
            renderizar();
        });
    });

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
        if (!sesion.ok) {
            throw new Error("No fue posible verificar la sesión.");
        }

        document.getElementById("user-badge").textContent =
            `${sesion.nombreCompleto || "Usuario"} • ${sesion.claveEmpleado || ""}`;
        document.getElementById("user-juzgado").textContent =
            `| ${sesion.nombreJuzgado || "Sin juzgado asignado"}`;

        if (sesion.rol === "ADMIN_OFICIALIA" || sesion.rol === "SUPER_ADMIN") {
            document.getElementById("link-volver-admin").classList.remove("d-none");
        } else {
            mostrarMensaje("danger", "La consulta de expedientes está disponible sólo para administradores de Oficialía.");
            window.setTimeout(() => {
                window.location.replace("index.html");
            }, 1200);
            return;
        }
        if (sesion.rol === "ADMIN_OFICIALIA") {
            document.getElementById("link-historial").classList.remove("d-none");
        }

        await cargarExpedientes();
    }

    async function cargarExpedientes() {
        mostrarMensaje("info", "Cargando expedientes...");
        const respuestaActual = await fetch(
            api("api/oficialia/mesas-oficio?accion=expedientes"),
            { credentials: "same-origin", headers: { Accept: "application/json" } }
        );
        const datosActuales = await respuestaActual.json();
        if (!respuestaActual.ok || !datosActuales.ok) {
            throw new Error(datosActuales.mensaje || `Error HTTP ${respuestaActual.status}`);
        }

        expedientes = (datosActuales.expedientes || []).map(expediente => ({
            ...expediente,
            cantidadSimilitudes: Number(expediente.cantidadSimilitudes) || 0,
            partes: obtenerPartes(expediente.partesJson, expediente.partes)
        }));

        try {
            const respuestaAnterior = await fetch(api("api/expedientes"), {
                credentials: "same-origin",
                headers: { Accept: "application/json" }
            });
            const datosAnteriores = await respuestaAnterior.json();
            if (!respuestaAnterior.ok || !datosAnteriores.ok) {
                throw new Error(datosAnteriores.mensaje ||
                    `Error HTTP ${respuestaAnterior.status}`);
            }

            expedientes.push(...(datosAnteriores.expedientes || []).map(expediente => ({
                folio: expediente.numeroExpediente,
                materia: expediente.materia,
                partes: obtenerPartes(null, expediente.partes),
                cantidadSimilitudes: 0,
                direccion: "",
                descripcion: "",
                fecha: ""
            })));
            limpiarMensaje();
        } catch (error) {
            mostrarMensaje(
                "warning",
                `No fue posible consultar registros anteriores: ${error.message}`
            );
        }

        renderizar();
    }

    function obtenerPartes(partesJson, partesTexto) {
        if (!partesJson) {
            return { promovente: partesTexto || "", demandado: "" };
        }
        let datos;
        try {
            datos = JSON.parse(partesJson || "{}");
        } catch (error) {
            console.error("No se pudieron leer las partes del expediente.", error);
            return { promovente: "", demandado: "" };
        }

        if (datos && typeof datos === "object" && !Array.isArray(datos)) {
            const promovente = datos.denunciaAnonima
                ? "Denuncia anónima"
                : datos.promovente?.nombreCompleto || "";
            return {
                promovente,
                demandado: datos.demandado?.nombreCompleto || ""
            };
        }

        const filas = Array.isArray(datos) ? datos
            : Array.isArray(datos.partes) ? datos.partes
                : [];
        return {
            promovente: filas.find(parte =>
                String(parte.rolParte || "").includes("DEMANDANTE")
            )?.nombreCompleto || "",
            demandado: filas.find(parte =>
                String(parte.rolParte || "").includes("DEMANDADO")
            )?.nombreCompleto || ""
        };
    }

    function renderizar() {
        const termino = filtro.value.trim().toLocaleLowerCase("es");
        const filtrados = expedientes.filter(expediente => {
            const partes = expediente.partes;
            return [
                expediente.folio, expediente.materia, partes.promovente,
                partes.demandado, expediente.direccion, expediente.descripcion,
                expediente.fecha
            ].some(valor => String(valor || "").toLocaleLowerCase("es").includes(termino));
        });

        filtrados.sort((a, b) => {
            const resultado = String(a[orden.campo] || "").localeCompare(
                String(b[orden.campo] || ""), "es", { numeric: true }
            );
            return orden.ascendente ? resultado : -resultado;
        });

        conteo.textContent = `${filtrados.length} de ${expedientes.length} expedientes`;
        tabla.replaceChildren();
        if (filtrados.length === 0) {
            const fila = document.createElement("tr");
            const celda = document.createElement("td");
            celda.colSpan = 7;
            celda.className = "text-center text-muted py-4";
            celda.textContent = expedientes.length
                ? "No hay expedientes que coincidan con la búsqueda."
                : "No hay expedientes registrados en su fiscalía.";
            fila.appendChild(celda);
            tabla.appendChild(fila);
            return;
        }

        filtrados.forEach(expediente => {
            const fila = document.createElement("tr");
            const cantidadSimilitudes =
                Number(expediente.cantidadSimilitudes) || 0;
            if (cantidadSimilitudes >= 3) {
                fila.classList.add("consulta-similitud-alta");
                fila.title = `${cantidadSimilitudes} criterios de similitud aceptados`;
            } else if (cantidadSimilitudes === 2) {
                fila.classList.add("consulta-similitud-media");
                fila.title = "2 criterios de similitud aceptados";
            } else if (cantidadSimilitudes === 1) {
                fila.classList.add("consulta-similitud-baja");
                fila.title = "1 criterio de similitud aceptado";
            }
            [
                expediente.folio,
                expediente.materia,
                expediente.partes.promovente,
                expediente.partes.demandado,
                expediente.direccion,
                expediente.fecha,
                expediente.descripcion
            ].forEach(valor => {
                const celda = document.createElement("td");
                celda.textContent = valor == null ? "" : String(valor);
                fila.appendChild(celda);
            });
            tabla.appendChild(fila);
        });
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
