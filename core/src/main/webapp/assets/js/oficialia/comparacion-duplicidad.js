"use strict";

(() => {
    const ETIQUETAS = Object.freeze({
        PROMOVENTE: "Nombre promovente",
        DEMANDADO: "Nombre demandado",
        RFC: "RFC/CURP",
        MATERIA: "Materia",
        DESCRIPCION: "Descripción"
    });

    function crearElemento(nombre, clases, texto) {
        const elemento = document.createElement(nombre);
        if (clases) elemento.className = clases;
        if (texto != null) elemento.textContent = String(texto);
        return elemento;
    }

    function mostrarValor(valor) {
        return valor == null || String(valor).trim() === ""
            ? "No proporcionado" : String(valor);
    }

    function clavesSimilitud(similitudes) {
        return new Set(Array.isArray(similitudes) ? similitudes : []);
    }

    function renderizarCriterios(contenedor, similitudes) {
        contenedor.replaceChildren();
        const criterios = Array.isArray(similitudes) ? similitudes : [];
        if (criterios.length === 0) {
            contenedor.textContent = "Sin criterios disponibles";
            return;
        }
        const clase = criterios.length >= 3
            ? "text-bg-danger"
            : criterios.length === 2
                ? "text-bg-warning"
                : "text-bg-info";
        criterios.forEach(criterio => {
            contenedor.appendChild(crearElemento(
                "span", `badge ${clase} me-1 mb-1`,
                ETIQUETAS[criterio] || criterio));
        });
    }

    function agregarFila(tabla, etiqueta, actual, similar, coincide) {
        const fila = crearElemento("tr", coincide
            ? "table-warning oficialia-comparacion-coincide" : "");
        const encabezado = crearElemento("th", "w-25", etiqueta);
        encabezado.scope = "row";
        if (coincide) {
            encabezado.appendChild(crearElemento(
                "span", "badge text-bg-warning ms-1",
                "Coincidencia"));
        }
        fila.append(
            encabezado,
            crearElemento("td", "", mostrarValor(actual)),
            crearElemento("td", "", mostrarValor(similar)));
        tabla.appendChild(fila);
    }

    function crearTablaComparacion() {
        const tabla = crearElemento("table", "table table-sm align-middle mb-0");
        const cabecera = document.createElement("thead");
        const filaCabecera = document.createElement("tr");
        ["Dato", "Trámite actual", "Expediente similar"].forEach(titulo => {
            filaCabecera.appendChild(crearElemento("th", "", titulo));
        });
        cabecera.appendChild(filaCabecera);
        tabla.append(cabecera, document.createElement("tbody"));
        return tabla;
    }

    function agregarFilaActual(tabla, etiqueta, valor) {
        const fila = document.createElement("tr");
        const encabezado = crearElemento("th", "w-25", etiqueta);
        encabezado.scope = "row";
        fila.append(encabezado, crearElemento("td", "", mostrarValor(valor)));
        tabla.appendChild(fila);
    }

    function parte(partes, propiedad) {
        if (!partes || typeof partes !== "object" || partes.denunciaAnonima) {
            return null;
        }
        const datos = partes[propiedad];
        return datos && typeof datos === "object" ? datos : null;
    }

    function nombreParte(partes, propiedad) {
        if (typeof partes === "string" && partes.trim()) {
            return partes;
        }
        const datos = parte(partes, propiedad);
        if (!datos) {
            return propiedad === "promovente" &&
                partes && partes.denunciaAnonima
                ? "Denuncia anónima" : "No proporcionado";
        }
        return mostrarValor(datos.nombreCompleto);
    }

    function identificadorParte(partes, propiedad) {
        const datos = parte(partes, propiedad);
        return datos ? mostrarValor(datos.curpRfc) : "No proporcionado";
    }

    function nombreActual(parteActual) {
        return parteActual && parteActual.nombreCompleto
            ? parteActual.nombreCompleto : "No proporcionado";
    }

    function identificadorActual(parteActual) {
        return parteActual && parteActual.curpRfc
            ? parteActual.curpRfc : "No proporcionado";
    }

    function claveCoincidencia(coincidencia) {
        if (coincidencia.idCoincidencia != null) {
            return `${coincidencia.fuente || ""}:${coincidencia.idCoincidencia}`;
        }
        return `${coincidencia.folio || ""}:${coincidencia.fecha || ""}`;
    }

    function renderizarTramiteActual(contenedor, actual) {
        contenedor.replaceChildren();
        const seccion = crearElemento("section", "card border-success-subtle");
        const cuerpo = crearElemento("div", "card-body p-3");
        const tabla = crearElemento("table", "table table-sm align-middle mb-0");
        const cabecera = document.createElement("thead");
        const filaCabecera = document.createElement("tr");
        ["Dato", "Información capturada"].forEach(titulo =>
            filaCabecera.appendChild(crearElemento("th", "", titulo)));
        cabecera.appendChild(filaCabecera);
        const cuerpoTabla = document.createElement("tbody");
        const promovente = actual.promovente || {};
        const demandado = actual.demandado || {};
        agregarFilaActual(cuerpoTabla, "Materia", actual.materia);
        agregarFilaActual(
            cuerpoTabla, "Nombre del promovente", nombreActual(promovente));
        agregarFilaActual(
            cuerpoTabla, "RFC/CURP del promovente",
            identificadorActual(promovente));
        agregarFilaActual(
            cuerpoTabla, "Nombre del demandado", nombreActual(demandado));
        agregarFilaActual(
            cuerpoTabla, "RFC/CURP del demandado",
            identificadorActual(demandado));
        tabla.append(cabecera, cuerpoTabla);
        cuerpo.appendChild(tabla);
        seccion.appendChild(cuerpo);
        contenedor.appendChild(seccion);
    }

    function renderizarDetalleCoincidencias(
        contenedor, actual, coincidencias, detalles) {
        contenedor.replaceChildren();
        const detallePorClave = new Map(
            detalles.map(detalle => [claveCoincidencia(detalle), detalle]));

        coincidencias.forEach((coincidencia, indice) => {
            const detalle = detallePorClave.get(claveCoincidencia(coincidencia))
                || detalles[indice];
            if (!detalle) return;
            const criterios = clavesSimilitud(
                detalle.similitudes || coincidencia.similitudes);
            const tarjeta = crearElemento(
                "article", "card mb-3 oficialia-comparacion-expediente");
            const cuerpo = crearElemento("div", "card-body p-3");
            const encabezado = crearElemento(
                "div", "d-flex flex-wrap justify-content-between gap-2 mb-2");
            const titulo = crearElemento(
                "h4", "h6 fw-bold mb-0",
                `${detalle.folio || coincidencia.folio || "Sin folio"} • `
                + `${detalle.materia || coincidencia.materia || "Sin materia"}`);
            const etiquetas = document.createElement("div");
            renderizarCriterios(
                etiquetas, [...criterios]);
            encabezado.append(titulo, etiquetas);
            cuerpo.appendChild(encabezado);

            const metadata = crearElemento(
                "p", "small text-muted mb-2",
                `${detalle.juzgado || coincidencia.juzgado || "Juzgado no disponible"}`
                + ` • ${detalle.distrito || coincidencia.distrito || "Distrito no disponible"}`
                + ` • ${detalle.fecha || coincidencia.fecha || "Fecha no disponible"}`);
            cuerpo.appendChild(metadata);

            const tabla = crearTablaComparacion();
            const filas = tabla.tBodies[0];
            const partes = detalle.partes;
            const coincideNombres = criterios.has("PROMOVENTE") ||
                criterios.has("DEMANDADO");
            const nombresSimilares = typeof partes === "string"
                ? partes
                : `Promovente: ${nombreParte(partes, "promovente")}; `
                    + `Demandado: ${nombreParte(partes, "demandado")}`;
            agregarFila(
                filas, "Materia", actual.materia,
                detalle.materia || coincidencia.materia,
                criterios.has("MATERIA"));
            agregarFila(
                filas, "Nombres de las partes",
                `Promovente: ${nombreActual(actual.promovente)}; `
                    + `Demandado: ${nombreActual(actual.demandado)}`,
                nombresSimilares,
                coincideNombres);
            agregarFila(
                filas, "Identificadores RFC/CURP",
                `Promovente: ${identificadorActual(actual.promovente)}; `
                    + `Demandado: ${identificadorActual(actual.demandado)}`,
                `Promovente: ${identificadorParte(partes, "promovente")}; `
                    + `Demandado: ${identificadorParte(partes, "demandado")}`,
                criterios.has("RFC"));
            cuerpo.appendChild(tabla);
            tarjeta.appendChild(cuerpo);
            tarjeta.setAttribute(
                "aria-label", `Comparación del expediente similar ${indice + 1}`);
            contenedor.appendChild(tarjeta);
        });

        if (contenedor.childElementCount === 0) {
            contenedor.appendChild(crearElemento(
                "div", "alert alert-warning",
                "No fue posible asociar los detalles de las similitudes con los resultados de búsqueda."));
        }
    }

    function mostrarError(contenedor, mensaje) {
        contenedor.replaceChildren(crearElemento(
            "div", "alert alert-warning mb-0",
            `No fue posible cargar los datos comparados: ${mensaje}`));
        contenedor.classList.remove("d-none");
    }

    window.OficialiaComparison = Object.freeze({
        renderizarCriterios,
        renderizarTramiteActual,
        renderizarDetalleCoincidencias,
        mostrarError
    });
})();
