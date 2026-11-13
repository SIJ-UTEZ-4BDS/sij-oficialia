"use strict";

document.addEventListener("DOMContentLoaded", () => {
    const API = "../../api/oficialia/mesas-oficio";
    const API_DUPLICIDAD = "../../api/oficialia/duplicidad";
    const form = document.getElementById("form-oficio");
    const aviso = document.getElementById("mensaje-registro");
    const validaciones = window.OficialiaValidation;
    const formElements = {
        demandado: document.getElementById("nombre-demandado"),
        rfcDemandado: document.getElementById("rfc-demandado"),
        materia: document.getElementById("idMateria"),
        materiaTexto: document.getElementById("materia-mostrada"),
        telefonoPromovente: document.getElementById("telefono-promovente"),
        correoPromovente: document.getElementById("correo-promovente"),
        direccion: {
            calle: document.getElementById("calle"),
            numeroExterior: document.getElementById("numero-exterior"),
            numeroInterior: document.getElementById("numero-interior"),
            colonia: document.getElementById("colonia"),
            municipio: document.getElementById("municipio"),
            estado: document.getElementById("estado"),
            codigoPostal: document.getElementById("codigo-postal")
        },
        descripcion: document.getElementById("descripcion"),
        anonima: document.getElementById("check-denuncia-anonima"),
        promovente: document.getElementById("resumen-partes")
    };
    const camposTocados = new Set();
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
    let puedeVerDetalle = false;
    let coincidenciasActuales = [];
    let solicitudDetalle = 0;

    recuperarParte1();
    inicializarSesion().catch(error => mostrarError(error.message));

    document.getElementById("btn-detalle-duplicidad")
        .addEventListener("click", verDetalles);
    document.getElementById("btn-continuar-duplicidad")
        .addEventListener("click", () => resolverDuplicidad("CONTINUAR"));
    document.getElementById("btn-cancelar-duplicidad")
        .addEventListener("click", () => resolverDuplicidad("CANCELAR"));
    form.addEventListener("input", event => {
        camposTocados.add(event.target);
        validarFormulario();
        limpiarMensaje();
    });
    form.addEventListener("change", event => {
        camposTocados.add(event.target);
        validarFormulario();
        limpiarMensaje();
    });
    form.addEventListener("focusout", event => {
        camposTocados.add(event.target);
        validarFormulario();
    });
    formElements.telefonoPromovente.addEventListener("input", () => {
        formElements.telefonoPromovente.value =
            validaciones.limpiarTelefonoCaptura(
                formElements.telefonoPromovente.value);
    });

    function recuperarParte1() {
        formElements.direccion.estado.value = "Morelos";
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
            ? "Denuncia anónima. El nombre y RFC/CURP no se guardarán; el contacto será visible sólo para el administrador."
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
        puedeVerDetalle = sesion.rol === "OPERADOR_OFICIALIA";
        if (sesion.rol === "ADMIN_OFICIALIA" ||
            sesion.rol === "SUPER_ADMIN") {
            document.getElementById("link-consultar")
                .classList.remove("d-none");
        }
        if (sesion.rol === "ADMIN_OFICIALIA") {
            document.getElementById("link-historial")
                .classList.remove("d-none");
        }
        if (!puedeVerDetalle) {
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
        if (!parte1 || !validarFormulario(true)) return;
        verificarAntesDeConfirmar().catch(error => mostrarError(error.message));
    });

    function validarFormulario(forzar = false) {
        let valido = true;
        const reglas = [
            [formElements.demandado,
                formElements.demandado.value.trim().length >= 2
                    ? "" : "El demandado es obligatorio."],
            [formElements.rfcDemandado,
                validaciones.validarIdentificador(
                    formElements.rfcDemandado.value).mensaje || ""],
            [formElements.direccion.calle,
                validaciones.textoDireccionValido(
                    formElements.direccion.calle.value)
                    ? "" : "Escribe una calle válida."],
            [formElements.direccion.numeroExterior,
                validaciones.numeroDomicilioValido(
                    formElements.direccion.numeroExterior.value)
                    ? "" : "Escribe el número exterior."],
            [formElements.direccion.numeroInterior,
                !formElements.direccion.numeroInterior.value.trim() ||
                validaciones.numeroDomicilioValido(
                    formElements.direccion.numeroInterior.value)
                    ? "" : "El número interior tiene un formato no válido."],
            [formElements.direccion.colonia,
                validaciones.textoDireccionValido(
                    formElements.direccion.colonia.value)
                    ? "" : "Escribe la colonia."],
            [formElements.direccion.municipio,
                validaciones.textoDireccionValido(
                    formElements.direccion.municipio.value)
                    ? "" : "Escribe el municipio."],
            [formElements.direccion.estado,
                formElements.direccion.estado.value === "Morelos"
                    ? "" : "El estado para este módulo es Morelos."],
            [formElements.direccion.codigoPostal,
                /^\d{5}$/.test(
                    formElements.direccion.codigoPostal.value.trim())
                    ? "" : "El código postal debe contener cinco dígitos."],
            [formElements.descripcion,
                formElements.descripcion.value.trim()
                    ? "" : "La descripción de los hechos es obligatoria."]
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

        const telefono = formElements.telefonoPromovente.value.trim();
        const correo = formElements.correoPromovente.value.trim();
        const telefonoNormalizado = validaciones.normalizarTelefono(telefono);
        const telefonoError = telefono && !telefonoNormalizado
            ? "Escribe exactamente diez dígitos; el prefijo +52 ya está incluido."
            : "";
        const correoError = correo && !validaciones.esCorreoValido(correo)
            ? "Ingresa un correo electrónico válido."
            : "";
        const contactoVacio = !telefono && !correo;
        const mostrarContacto = forzar || camposTocados.has(
            formElements.telefonoPromovente) || camposTocados.has(
            formElements.correoPromovente) || telefono || correo;
        if (telefonoError || contactoVacio) {
            valido = false;
            if (mostrarContacto) {
                validaciones.marcarInvalido(
                    formElements.telefonoPromovente,
                    telefonoError ||
                        "Proporciona un teléfono válido o un correo electrónico válido.");
            }
        } else if (mostrarContacto) {
            validaciones.marcarValido(formElements.telefonoPromovente);
        } else {
            validaciones.limpiarEstado(formElements.telefonoPromovente);
        }
        if (correoError || contactoVacio) {
            valido = false;
            if (mostrarContacto) {
                validaciones.marcarInvalido(
                    formElements.correoPromovente,
                    correoError ||
                        "Proporciona un teléfono válido o un correo electrónico válido.");
            }
        } else if (mostrarContacto) {
            validaciones.marcarValido(formElements.correoPromovente);
        } else {
            validaciones.limpiarEstado(formElements.correoPromovente);
        }
        return valido;
    }

    function leerDireccion() {
        const direccion = Object.fromEntries(
            Object.entries(formElements.direccion).map(([clave, elemento]) =>
                [clave, elemento.value.trim()]));
        direccion.estado = "Morelos";
        return direccion;
    }

    function direccionCompleta() {
        return validaciones.formatearDireccion(leerDireccion());
    }

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
            if (puedeVerDetalle) {
                verDetalles();
            }
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
        const correo = formElements.correoPromovente.value.trim()
            .toLowerCase();
        [
            ["Promovente", promovente],
            ["Parte demandada", formElements.demandado.value.trim()],
            ["Materia", formElements.materiaTexto.value],
            ["Teléfono de quien demanda (+52)",
                validaciones.normalizarTelefono(
                    formElements.telefonoPromovente.value)
                    ? `+52 ${validaciones.normalizarTelefono(
                        formElements.telefonoPromovente.value)}`
                    : "—"],
            ["Correo de quien demanda", correo || "—"],
            ["Dirección", direccionCompleta()],
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
        if (enviando || !parte1 || !validarFormulario(true)) return;
        enviando = true;
        btnGuardar.disabled = true;

        const anonima = formElements.anonima.checked;
        const direccion = leerDireccion();
        const partes = {
            idMateria: Number(parte1.idMateria),
            denunciaAnonima: anonima,
            direccionComponentes: direccion,
            promovente: {
                nombreCompleto: anonima
                    ? "" : parte1.promovente.nombreCompleto.trim().toUpperCase(),
                curpRfc: anonima
                    ? "" : (parte1.promovente.curpRfc || "")
                        .trim().toUpperCase(),
                tipoPersona: parte1.promovente.tipoPersona,
                rolParte: "DEMANDANTE",
                contacto: {
                    telefono: validaciones.normalizarTelefono(
                        formElements.telefonoPromovente.value),
                    correoElectronico: formElements.correoPromovente.value
                        .trim().toLowerCase()
                }
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
            direccion: direccionCompleta(),
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
            const resultado = await validaciones.leerRespuestaJson(
                respuesta, "guardar el oficio");
            if (respuesta.status === 409 && resultado.duplicado) {
                aceptarDuplicidad = false;
                reintentarGuardado = true;
                mostrarCoincidencias(resultado.coincidencias || []);
                document.getElementById("resumen-duplicidad").textContent =
                    `La verificación final detectó ${resultado.coincidencias?.length || 0} posibles coincidencias. Revise y decida si desea continuar.`;
                document.getElementById("modal-confirmacion")
                    .addEventListener(
                        "hidden.bs.modal",
                        () => {
                            modalDuplicidad.show();
                            if (puedeVerDetalle) {
                                verDetalles();
                            }
                        },
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
        const resultado = await validaciones.leerRespuestaJson(
            respuesta, "consultar posibles duplicidades");
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
        if (!puedeVerDetalle) return;
        const boton = document.getElementById("btn-detalle-duplicidad");
        const panel = document.getElementById("detalle-duplicidad");
        const solicitud = ++solicitudDetalle;
        boton.disabled = true;
        boton.textContent = "Cargando comparación...";
        try {
            const result = await enviarDuplicidad("detalle");
            if (solicitud !== solicitudDetalle) return;
            window.OficialiaComparison.renderizarDetalleCoincidencias(
                panel,
                datosComparacionActual(),
                coincidenciasActuales,
                result.detalles || []);
            panel.classList.remove("d-none");
        } catch (error) {
            window.OficialiaComparison.mostrarError(panel, error.message);
        } finally {
            boton.disabled = false;
            boton.innerHTML =
                '<i class="bi bi-arrow-clockwise me-1"></i>Actualizar comparación';
        }
    }

    function mostrarCoincidencias(coincidencias) {
        solicitudDetalle++;
        coincidenciasActuales = Array.isArray(coincidencias)
            ? coincidencias : [];
        window.OficialiaComparison.renderizarTramiteActual(
            document.getElementById("comparacion-tramite-actual"),
            datosComparacionActual());
        const tabla = document.getElementById("tabla-duplicidad");
        tabla.replaceChildren();
        coincidenciasActuales.forEach(coincidencia => {
            const fila = document.createElement("tr");
            const valores = [
                coincidencia.folio,
                coincidencia.juzgado,
                coincidencia.distrito,
                coincidencia.materia
            ];
            valores.forEach(valor => {
                const celda = document.createElement("td");
                celda.textContent = valor == null ? "" : String(valor);
                fila.appendChild(celda);
            });
            const criterios = document.createElement("td");
            window.OficialiaComparison.renderizarCriterios(
                criterios, coincidencia.similitudes);
            fila.appendChild(criterios);
            tabla.appendChild(fila);
        });
        document.getElementById("detalle-duplicidad").replaceChildren();
        document.getElementById("detalle-duplicidad")
            .classList.add("d-none");
    }

    function datosComparacionActual() {
        return {
            materia: formElements.materiaTexto.value,
            promovente: {
                nombreCompleto:
                    parte1?.promovente?.nombreCompleto || "",
                curpRfc: parte1?.promovente?.curpRfc || ""
            },
            demandado: {
                nombreCompleto: formElements.demandado.value.trim(),
                curpRfc: formElements.rfcDemandado.value.trim()
            }
        };
    }

    function mostrarError(mensaje) {
        aviso.className = "alert alert-danger mb-3";
        aviso.textContent = mensaje;
    }

    function limpiarMensaje() {
        aviso.className = "alert d-none mb-3";
        aviso.textContent = "";
    }
});
