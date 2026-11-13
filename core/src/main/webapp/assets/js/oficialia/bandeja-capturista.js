"use strict";

document.addEventListener("DOMContentLoaded", () => {
    const api = ruta => new URL(
        ruta, new URL("../../", window.location.href)).toString();
    const tabla = document.getElementById("tabla-bandeja");
    const filtro = document.getElementById("buscar-bandeja");
    const aviso = document.getElementById("mensaje-bandeja");
    const conteo = document.getElementById("conteo-bandeja");
    const formulario = document.getElementById("form-editar-contacto");
    const modalElemento = document.getElementById("modal-editar-contacto");
    const modal = new bootstrap.Modal(modalElemento);
    const avisoEdicion = document.getElementById("mensaje-editar-contacto");
    const camposDireccion = {
        calle: document.getElementById("contacto-calle"),
        numeroExterior: document.getElementById("contacto-exterior"),
        numeroInterior: document.getElementById("contacto-interior"),
        colonia: document.getElementById("contacto-colonia"),
        municipio: document.getElementById("contacto-municipio"),
        estado: "Morelos",
        codigoPostal: document.getElementById("contacto-cp")
    };
    const telefono = document.getElementById("contacto-telefono");
    const correo = document.getElementById("contacto-correo");
    let expedientes = [];
    let expedienteActual = null;

    filtro.addEventListener("input", renderizar);
    document.getElementById("btn-actualizar-bandeja")
        .addEventListener("click", () =>
            cargar().catch(error => mostrarMensaje("danger", error.message)));
    telefono.addEventListener("input", () => {
        telefono.value = telefono.value.replace(/\D/g, "").slice(0, 10);
    });
    formulario.addEventListener("submit", guardarCorreccion);
    inicializar().catch(error => mostrarMensaje("danger", error.message));

    async function inicializar() {
        const sesion = await solicitar("api/auth/session");
        if (sesion.rol !== "OPERADOR_OFICIALIA") {
            mostrarMensaje("danger", "La bandeja está disponible sólo para capturistas de Oficialía.");
            window.setTimeout(() => window.location.replace("index.html"), 1000);
            return;
        }
        document.getElementById("user-badge").textContent =
            `${sesion.nombreCompleto || "Usuario"} • ${sesion.claveEmpleado || ""}`;
        document.getElementById("user-juzgado").textContent =
            `| ${sesion.nombreJuzgado || "Sin juzgado asignado"}`;
        await cargar();
    }

    async function cargar() {
        mostrarMensaje("info", "Cargando carátulas del juzgado...");
        const datos = await solicitar(
            "api/oficialia/mesas-oficio?accion=expedientes");
        expedientes = Array.isArray(datos.expedientes)
            ? datos.expedientes : [];
        renderizar();
        limpiarMensaje();
    }

    function renderizar() {
        const termino = filtro.value.trim().toLocaleLowerCase("es");
        const encontrados = expedientes.filter(expediente =>
            [expediente.folio, expediente.materia, expediente.mesa,
                expediente.fecha, expediente.estatus]
                .some(valor => String(valor || "")
                    .toLocaleLowerCase("es").includes(termino)));
        conteo.textContent = `${encontrados.length} de ${expedientes.length}`;
        tabla.replaceChildren();
        if (encontrados.length === 0) {
            const fila = document.createElement("tr");
            const celda = document.createElement("td");
            celda.colSpan = 6;
            celda.className = "text-center text-muted py-4";
            celda.textContent = expedientes.length
                ? "No hay carátulas que coincidan con la búsqueda."
                : "No hay expedientes registrados en este juzgado.";
            fila.appendChild(celda);
            tabla.appendChild(fila);
            return;
        }
        encontrados.forEach(expediente => {
            const fila = document.createElement("tr");
            [expediente.folio, expediente.materia, expediente.mesa,
                expediente.fecha, expediente.estatus]
                .forEach(valor => {
                    const celda = document.createElement("td");
                    celda.textContent = valor == null ? "—" : String(valor);
                    fila.appendChild(celda);
                });
            const accion = document.createElement("td");
            if (expediente.fuente === "REGISTRO"
                    && Number(expediente.idRegistro) > 0) {
                const boton = document.createElement("button");
                boton.type = "button";
                boton.className = "btn btn-sm btn-outline-secondary";
                boton.title = "Corregir teléfono, correo o domicilio";
                boton.setAttribute("aria-label",
                    `Corregir contacto o domicilio del expediente ${expediente.folio}`);
                const icono = document.createElement("i");
                icono.className = "bi bi-pencil-square me-1";
                boton.append(icono, document.createTextNode("Corregir contacto/domicilio"));
                boton.addEventListener("click", () => abrirEditor(expediente));
                accion.appendChild(boton);
            } else {
                accion.textContent = "Solo lectura";
            }
            fila.appendChild(accion);
            tabla.appendChild(fila);
        });
    }

    async function abrirEditor(expediente) {
        try {
            expedienteActual = expediente;
            formulario.reset();
            limpiarMensajeEdicion();
            const respuesta = await solicitar(
                `api/oficialia/mesas-oficio?accion=contacto&idRegistro=${encodeURIComponent(expediente.idRegistro)}`);
            const datos = respuesta.datos || {};
            telefono.value = datos.telefono || "";
            correo.value = datos.correoElectronico || "";
            const direccion = datos.direccionComponentes || {};
            Object.entries(camposDireccion).forEach(([campo, elemento]) => {
                if (typeof elemento !== "string") {
                    elemento.value = direccion[campo] || "";
                }
            });
            modal.show();
        } catch (error) {
            mostrarMensaje("danger", error.message);
        }
    }

    async function guardarCorreccion(evento) {
        evento.preventDefault();
        if (!expedienteActual) return;
        if (!formulario.checkValidity()) {
            formulario.reportValidity();
            return;
        }
        const telefonoValor = telefono.value.trim();
        const correoValor = correo.value.trim();
        if (!telefonoValor && !correoValor) {
            mostrarMensajeEdicion(
                "danger", "Proporciona un teléfono o correo electrónico válido.");
            return;
        }
        if (telefonoValor && !/^\d{10}$/.test(telefonoValor)) {
            mostrarMensajeEdicion("danger", "El teléfono debe contener diez dígitos.");
            return;
        }
        if (correoValor && !correo.checkValidity()) {
            mostrarMensajeEdicion("danger", "El correo electrónico no es válido.");
            return;
        }
        const direccion = Object.fromEntries(
            Object.entries(camposDireccion).map(([campo, elemento]) => [
                campo, typeof elemento === "string" ? elemento : elemento.value.trim()
            ]));
        const params = new URLSearchParams({
            accion: "actualizar-contacto",
            idRegistro: String(expedienteActual.idRegistro),
            telefono: telefonoValor,
            correoElectronico: correoValor,
            direccionComponentes: JSON.stringify(direccion)
        });
        const boton = formulario.querySelector('[type="submit"]');
        boton.disabled = true;
        try {
            const resultado = await solicitar(
                "api/oficialia/mesas-oficio", {
                    method: "POST",
                    headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
                    body: params
                });
            modal.hide();
            mostrarMensaje("success", resultado.mensaje || "Corrección guardada.");
            await cargar();
        } catch (error) {
            mostrarMensajeEdicion("danger", error.message);
        } finally {
            boton.disabled = false;
        }
    }

    async function solicitar(ruta, opciones = {}) {
        const respuesta = await fetch(api(ruta), {
            credentials: "same-origin",
            headers: { Accept: "application/json", ...(opciones.headers || {}) },
            ...opciones
        });
        const texto = await respuesta.text();
        let datos;
        try {
            datos = JSON.parse(texto);
        } catch (error) {
            throw new Error(`Respuesta inválida del servidor (HTTP ${respuesta.status}).`);
        }
        if (!respuesta.ok || !datos.ok) {
            throw new Error(datos.mensaje || `Error HTTP ${respuesta.status}.`);
        }
        return datos;
    }

    function mostrarMensaje(tipo, texto) {
        aviso.className = `alert alert-${tipo}`;
        aviso.textContent = texto;
    }

    function limpiarMensaje() {
        aviso.className = "alert d-none";
        aviso.textContent = "";
    }

    function mostrarMensajeEdicion(tipo, texto) {
        avisoEdicion.className = `alert alert-${tipo}`;
        avisoEdicion.textContent = texto;
    }

    function limpiarMensajeEdicion() {
        avisoEdicion.className = "alert d-none";
        avisoEdicion.textContent = "";
    }
});
