"use strict";

document.addEventListener("DOMContentLoaded", () => {
    const contexto = new URL("../../", window.location.href);
    const api = ruta => new URL(ruta, contexto).toString();
    const tabla = document.getElementById("tabla-expedientes");
    const filtro = document.getElementById("buscar-expedientes");
    const aviso = document.getElementById("mensaje-consulta");
    const conteo = document.getElementById("resultado-conteo");
    const modalDetalle = new bootstrap.Modal(
        document.getElementById("modal-detalle-expediente"));
    const contenidoDetalle =
        document.getElementById("contenido-detalle-expediente");
    const modalEditar = new bootstrap.Modal(
        document.getElementById("modal-editar-expediente"));
    const formularioEditar = document.getElementById("form-editar-expediente");
    const modalArchivo = new bootstrap.Modal(
        document.getElementById("modal-confirmar-archivo"));
    const mensajeEdicion =
        document.getElementById("mensaje-editar-expediente");
    const formularioCampos = {
        promovente: document.getElementById("admin-promovente"),
        rfcPromovente: document.getElementById("admin-rfc-promovente"),
        demandado: document.getElementById("admin-demandado"),
        rfcDemandado: document.getElementById("admin-rfc-demandado"),
        tipoPersona: document.getElementById("admin-tipo-persona"),
        telefono: document.getElementById("admin-telefono"),
        correo: document.getElementById("admin-correo"),
        anonima: document.getElementById("admin-anonima"),
        calle: document.getElementById("admin-calle"),
        exterior: document.getElementById("admin-exterior"),
        interior: document.getElementById("admin-interior"),
        colonia: document.getElementById("admin-colonia"),
        municipio: document.getElementById("admin-municipio"),
        cp: document.getElementById("admin-cp"),
        descripcion: document.getElementById("admin-descripcion"),
        partesResumen: document.getElementById("admin-partes-resumen"),
        tipoParte: document.getElementById("admin-tipo-parte")
    };

    let expedientes = [];
    let orden = { campo: "fecha", ascendente: false };
    let rolActual = null;
    let expedienteEditando = null;
    let expedienteArchivando = null;
    let materias = [];

    document.getElementById("btn-actualizar").addEventListener("click", () => {
        cargarExpedientes().catch(error => mostrarMensaje("danger", error.message));
    });
    filtro.addEventListener("input", renderizar);
    formularioEditar.addEventListener("submit", guardarEdicion);
    formularioCampos.anonima.addEventListener("change", actualizarAnonimato);
    formularioCampos.telefono.addEventListener("input", () => {
        formularioCampos.telefono.value = formularioCampos.telefono.value
            .replace(/\D/g, "").slice(0, 10);
    });
    document.getElementById("admin-materia")
        .addEventListener("change", () => {
            cargarMesas(Number(document.getElementById("admin-materia").value))
                .catch(error => mostrarMensajeEdicion(error.message));
        });
    document.getElementById("btn-confirmar-archivo")
        .addEventListener("click", confirmarArchivo);
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
        rolActual = sesion.rol;

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
            partes: obtenerPartes(
                expediente.partesJson, expediente.partes, expediente.fuente)
        }));
        limpiarMensaje();
        renderizar();
    }

    function obtenerPartes(partesJson, partesTexto, fuente) {
        if (fuente === "LEGADO") {
            return {
                promovente: partesTexto || partesJson || "",
                demandado: ""
            };
        }
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
            celda.colSpan = 12;
            celda.className = "text-center text-muted py-4";
            celda.textContent = expedientes.length
                ? "No hay expedientes que coincidan con la búsqueda."
                : "No hay expedientes registrados en su juzgado.";
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
                expediente.mesa,
                expediente.estatus,
                expediente.partes.promovente,
                expediente.partes.demandado,
                expediente.direccion,
                expediente.fecha,
                expediente.descripcion,
                expediente.capturista
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
            if (rolActual === "ADMIN_OFICIALIA") {
                const botonEditar = document.createElement("button");
                botonEditar.type = "button";
                botonEditar.className = "btn btn-sm btn-outline-primary me-1";
                botonEditar.title = "Editar expediente";
                botonEditar.setAttribute("aria-label",
                    `Editar expediente ${expediente.folio || ""}`);
                const iconoEditar = document.createElement("i");
                iconoEditar.className = "bi bi-pencil-square";
                botonEditar.appendChild(iconoEditar);
                botonEditar.addEventListener("click", () =>
                    abrirEdicion(expediente));
                celdaAcciones.appendChild(botonEditar);

                const botonArchivar = document.createElement("button");
                botonArchivar.type = "button";
                botonArchivar.className = "btn btn-sm btn-outline-danger";
                botonArchivar.title = "Archivar expediente";
                botonArchivar.setAttribute("aria-label",
                    `Archivar expediente ${expediente.folio || ""}`);
                const iconoArchivar = document.createElement("i");
                iconoArchivar.className = "bi bi-archive";
                botonArchivar.appendChild(iconoArchivar);
                botonArchivar.addEventListener("click", () => {
                    expedienteArchivando = expediente;
                    modalArchivo.show();
                });
                celdaAcciones.appendChild(botonArchivar);
            } else {
                celdaAcciones.textContent = "—";
            }
            fila.appendChild(celdaAcciones);
            tabla.appendChild(fila);
        });
    }

    function mostrarDetalle(expediente) {
        let datosPartes = expediente.partesJson || expediente.partes || {};
        if (typeof datosPartes === "string") {
            try {
                datosPartes = JSON.parse(datosPartes);
            } catch (error) {
                console.error("No se pudieron leer los datos del expediente.", error);
                datosPartes = {};
            }
        }
        const promovente = datosPartes.promovente || {};
        const demandado = datosPartes.demandado || {};
        const contacto = promovente.contacto || {};
        const anonimato = Boolean(datosPartes.denunciaAnonima);
        const campos = [
            ["Folio", expediente.folio],
            ["Materia", expediente.materia],
            ["Mesa asignada", expediente.mesa],
            ["Fecha de registro", expediente.fecha],
            ["Estado", expediente.estatus || "Registrado"],
            ["Capturista", expediente.capturista],
            ["Promovente", anonimato
                ? "Denuncia anónima"
                : promovente.nombreCompleto
                    || (expediente.fuente === "LEGADO"
                        ? expediente.partesJson : expediente.partes?.promovente)],
            ["RFC/CURP del promovente",
                anonimato ? "" : promovente.curpRfc],
            ["Tipo de persona", promovente.tipoPersona],
            ["Teléfono de contacto", contacto.telefono],
            ["Correo electrónico", contacto.correoElectronico],
            ["Demandado", demandado.nombreCompleto ||
                expediente.partes?.demandado],
            ["RFC/CURP del demandado", demandado.curpRfc],
            ["Dirección", expediente.direccion],
            ["Descripción de los hechos", expediente.descripcion],
            ["Criterios de similitud admitidos",
                expediente.cantidadSimilitudes > 0
                    ? expediente.cantidadSimilitudes : "Ninguno"]
        ];
        if (expediente.fuente === "LEGADO") {
            campos.push(["Tipo de parte", expediente.tipoParte]);
        }
        const direccion = datosPartes.direccionComponentes;
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
        campos.forEach(([etiqueta, valor]) =>
            agregarCampoDetalle(contenidoDetalle, etiqueta, valor));
        modalDetalle.show();
    }

    function agregarCampoDetalle(contenedor, etiqueta, valor) {
        const fila = document.createElement("p");
        fila.className = "mb-2";
        const titulo = document.createElement("strong");
        titulo.textContent = `${etiqueta}: `;
        fila.append(titulo, document.createTextNode(
            valor == null || valor === "" ? "—" : String(valor)));
        contenedor.appendChild(fila);
    }

    async function abrirEdicion(expediente) {
        expedienteEditando = expediente;
        try {
            mostrarMensajeEdicion("");
            formularioEditar.reset();
            await cargarMaterias();
            const selectMateria = document.getElementById("admin-materia");
            selectMateria.replaceChildren(new Option("Selecciona materia", ""));
            materias.forEach(materia => selectMateria.add(
                new Option(materia.nombreMateria, materia.idMateria)));
            selectMateria.value = String(expediente.idMateria || "");
            document.getElementById("admin-estatus").value =
                expediente.estatus || "REGISTRADO";

            const esLegado = expediente.fuente === "LEGADO";
            document.getElementById("admin-fuente").value = expediente.fuente;
            document.getElementById("admin-id").value = String(
                esLegado ? expediente.idExpediente : expediente.idRegistro);
            document.getElementById("admin-registro-fields")
                .classList.toggle("d-none", esLegado);
            document.getElementById("admin-legado-fields")
                .classList.toggle("d-none", !esLegado);
            configurarRequeridosAdmin(esLegado);
            if (esLegado) {
                formularioCampos.partesResumen.value =
                    expediente.partes || expediente.partesJson || "";
                formularioCampos.tipoParte.value = expediente.tipoParte || "";
            } else {
                llenarCamposRegistro(expediente);
            }
            await cargarMesas(Number(selectMateria.value), expediente.idMesa);
            modalEditar.show();
        } catch (error) {
            mostrarMensaje("danger", error.message);
        }
    }

    function configurarRequeridosAdmin(esLegado) {
        const camposNuevos = [
            formularioCampos.demandado, formularioCampos.calle,
            formularioCampos.exterior, formularioCampos.colonia,
            formularioCampos.municipio, formularioCampos.cp,
            formularioCampos.descripcion
        ];
        camposNuevos.forEach(campo => campo.required = !esLegado);
        formularioCampos.partesResumen.required = esLegado;
        formularioCampos.tipoParte.required = esLegado;
        document.getElementById("admin-materia").required = true;
        document.getElementById("admin-mesa").required = true;
        document.getElementById("admin-estatus").required = true;
    }

    function llenarCamposRegistro(expediente) {
        let partes = expediente.partesJson || {};
        if (typeof partes === "string") {
            try {
                partes = JSON.parse(partes);
            } catch (error) {
                throw new Error(
                    "No se puede editar el expediente porque sus datos no tienen un formato válido.");
            }
        }
        const promovente = partes.promovente || {};
        const demandado = partes.demandado || {};
        const contacto = promovente.contacto || {};
        const direccion = partes.direccionComponentes || {};
        formularioCampos.promovente.value = promovente.nombreCompleto || "";
        formularioCampos.rfcPromovente.value = promovente.curpRfc || "";
        formularioCampos.demandado.value = demandado.nombreCompleto || "";
        formularioCampos.rfcDemandado.value = demandado.curpRfc || "";
        formularioCampos.tipoPersona.value = promovente.tipoPersona || "";
        formularioCampos.telefono.value = contacto.telefono || "";
        formularioCampos.correo.value = contacto.correoElectronico || "";
        formularioCampos.anonima.checked = Boolean(partes.denunciaAnonima);
        formularioCampos.calle.value = direccion.calle || "";
        formularioCampos.exterior.value = direccion.numeroExterior || "";
        formularioCampos.interior.value = direccion.numeroInterior || "";
        formularioCampos.colonia.value = direccion.colonia || "";
        formularioCampos.municipio.value = direccion.municipio || "";
        formularioCampos.cp.value = direccion.codigoPostal || "";
        formularioCampos.descripcion.value = expediente.descripcion || "";
        actualizarAnonimato();
    }

    function actualizarAnonimato() {
        const anonima = formularioCampos.anonima.checked;
        formularioCampos.promovente.disabled = anonima;
        formularioCampos.rfcPromovente.disabled = anonima;
        if (anonima) {
            formularioCampos.promovente.value = "";
            formularioCampos.rfcPromovente.value = "";
        }
    }

    async function cargarMaterias() {
        if (materias.length) return;
        const datos = await solicitar("api/oficialia/mesas-oficio?accion=materias");
        materias = datos.materias || [];
    }

    async function cargarMesas(idMateria, idMesaSeleccionada) {
        const select = document.getElementById("admin-mesa");
        select.replaceChildren(new Option("Cargando mesas...", ""));
        const datos = await solicitar(
            `api/oficialia/mesas-oficio?accion=mesas&idMateria=${encodeURIComponent(idMateria)}`);
        const mesas = datos.mesas || [];
        select.replaceChildren(new Option(
            mesas.length ? "Selecciona mesa" : "No hay mesas activas", ""));
        mesas.forEach(mesa => select.add(
            new Option(mesa.nombre, mesa.idMesa)));
        if (idMesaSeleccionada != null) {
            select.value = String(idMesaSeleccionada);
        }
        if (mesas.length === 0) {
            throw new Error("No hay mesas activas para esa materia en este juzgado.");
        }
    }

    async function guardarEdicion(evento) {
        evento.preventDefault();
        if (!expedienteEditando) return;
        if (!formularioEditar.checkValidity()) {
            formularioEditar.reportValidity();
            return;
        }
        const esLegado = expedienteEditando.fuente === "LEGADO";
        const params = new URLSearchParams({
            accion: "actualizar-expediente",
            fuente: expedienteEditando.fuente,
            idRegistro: document.getElementById("admin-id").value,
            idMateria: document.getElementById("admin-materia").value,
            idMesa: document.getElementById("admin-mesa").value,
            estatus: document.getElementById("admin-estatus").value.trim(),
            partesResumen: formularioCampos.partesResumen.value.trim(),
            tipoParte: formularioCampos.tipoParte.value.trim()
        });
        if (!esLegado) {
            if (!formularioCampos.anonima.checked
                    && !formularioCampos.promovente.value.trim()) {
                mostrarMensajeEdicion("Escribe el nombre del promovente o marca la denuncia anónima.");
                return;
            }
            if (!formularioCampos.telefono.value.trim()
                    && !formularioCampos.correo.value.trim()) {
                mostrarMensajeEdicion("Proporciona teléfono o correo electrónico de quien demanda.");
                return;
            }
            const direccion = {
                calle: formularioCampos.calle.value.trim(),
                numeroExterior: formularioCampos.exterior.value.trim(),
                numeroInterior: formularioCampos.interior.value.trim(),
                colonia: formularioCampos.colonia.value.trim(),
                municipio: formularioCampos.municipio.value.trim(),
                estado: "Morelos",
                codigoPostal: formularioCampos.cp.value.trim()
            };
            const partesAnteriores = JSON.parse(
                expedienteEditando.partesJson || "{}");
            const promoventeAnterior = partesAnteriores.promovente || {};
            const demandadoAnterior = partesAnteriores.demandado || {};
            const partes = {
                ...partesAnteriores,
                idMateria: Number(params.get("idMateria")),
                denunciaAnonima: formularioCampos.anonima.checked,
                direccionComponentes: direccion,
                promovente: {
                    ...promoventeAnterior,
                    nombreCompleto: formularioCampos.anonima.checked
                        ? "" : formularioCampos.promovente.value.trim(),
                    curpRfc: formularioCampos.anonima.checked
                        ? "" : formularioCampos.rfcPromovente.value.trim(),
                    tipoPersona: formularioCampos.tipoPersona.value,
                    contacto: {
                        ...(promoventeAnterior.contacto || {}),
                        telefono: formularioCampos.telefono.value.trim(),
                        correoElectronico: formularioCampos.correo.value.trim()
                    }
                },
                demandado: {
                    ...demandadoAnterior,
                    nombreCompleto: formularioCampos.demandado.value.trim(),
                    curpRfc: formularioCampos.rfcDemandado.value.trim()
                }
            };
            params.set("direccion", formatearDireccion(direccion));
            params.set("descripcion", formularioCampos.descripcion.value.trim());
            params.set("partesJson", JSON.stringify(partes));
        }
        const boton = formularioEditar.querySelector('[type="submit"]');
        boton.disabled = true;
        try {
            await enviarFormulario(params);
            modalEditar.hide();
            await cargarExpedientes();
            mostrarMensaje("success", "El expediente se actualizó correctamente.");
        } catch (error) {
            mostrarMensajeEdicion(error.message);
        } finally {
            boton.disabled = false;
        }
    }

    async function confirmarArchivo() {
        if (!expedienteArchivando) return;
        const esLegado = expedienteArchivando.fuente === "LEGADO";
        const params = new URLSearchParams({
            accion: "archivar",
            fuente: expedienteArchivando.fuente,
            idRegistro: String(esLegado
                ? expedienteArchivando.idExpediente
                : expedienteArchivando.idRegistro)
        });
        const boton = document.getElementById("btn-confirmar-archivo");
        boton.disabled = true;
        try {
            await enviarFormulario(params);
            modalArchivo.hide();
            expedienteArchivando = null;
            await cargarExpedientes();
            mostrarMensaje("success", "El expediente se archivó correctamente.");
        } catch (error) {
            mostrarMensaje("danger", error.message);
        } finally {
            boton.disabled = false;
        }
    }

    async function solicitar(ruta) {
        const respuesta = await fetch(api(ruta), {
            credentials: "same-origin",
            headers: { Accept: "application/json" }
        });
        return leerRespuesta(respuesta);
    }

    async function enviarFormulario(params) {
        const respuesta = await fetch(api("api/oficialia/mesas-oficio"), {
            method: "POST",
            credentials: "same-origin",
            headers: {
                Accept: "application/json",
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
            },
            body: params
        });
        return leerRespuesta(respuesta);
    }

    async function leerRespuesta(respuesta) {
        const cuerpo = await respuesta.text();
        let datos;
        try {
            datos = JSON.parse(cuerpo);
        } catch (error) {
            throw new Error(`Respuesta inválida del servidor (HTTP ${respuesta.status}).`);
        }
        if (!respuesta.ok || !datos.ok) {
            throw new Error(datos.mensaje || `Error HTTP ${respuesta.status}.`);
        }
        return datos;
    }

    function formatearDireccion(direccion) {
        const segmentos = [
            `Calle ${direccion.calle}`,
            `No. exterior ${direccion.numeroExterior}`
        ];
        if (direccion.numeroInterior) {
            segmentos.push(`Interior ${direccion.numeroInterior}`);
        }
        segmentos.push(
            `Colonia ${direccion.colonia}`,
            `Municipio ${direccion.municipio}`,
            `Estado ${direccion.estado}`,
            `C.P. ${direccion.codigoPostal}`);
        return segmentos.join(", ");
    }

    function mostrarMensajeEdicion(texto) {
        mensajeEdicion.className = texto ? "alert alert-danger" : "alert d-none";
        mensajeEdicion.textContent = texto;
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
