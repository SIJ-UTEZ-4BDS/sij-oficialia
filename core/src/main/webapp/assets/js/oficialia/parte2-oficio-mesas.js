
"use strict";

document.addEventListener("DOMContentLoaded", () => {

    const API = "../../api/oficialia/mesas-oficio";

    const form = document.getElementById("form-oficio");
    const materia = document.getElementById("idMateria");
    const direccion = document.getElementById("direccion");
    const descripcion = document.getElementById("descripcion");

    const panelMesa = document.getElementById("panel-mesa");
    const mesaAsignada = document.getElementById("mesa-asignada");

    const tablaMesas = document.getElementById("tabla-mesas");

    const modalConfirmacion = new bootstrap.Modal(
        document.getElementById("modal-confirmacion")
    );

    const modalExito = new bootstrap.Modal(
        document.getElementById("modal-exito")
    );

    const btnGuardar = document.getElementById("btn-guardar");
    const btnActualizar = document.getElementById("btn-actualizar");

    const partesGuardadas =
        sessionStorage.getItem("oficialia_parte1_validada");

    let partes = null;
    let mesaSeleccionada = null;
    let folioRegistrado = null;
    let enviando = false;

    /*
     * Recuperar información de la Parte 1.
     */
    function recuperarPartes() {

        const contenedor = document.getElementById("resumen-partes");

        if (!partesGuardadas) {
            contenedor.textContent =
                "No se encontraron datos validados de la Parte 1.";

            mostrarErrorParte1();
            return false;
        }

        try {

            partes = JSON.parse(partesGuardadas);

            if (!partes ||
                typeof partes !== "object" ||
                Array.isArray(partes)) {

                throw new Error("Formato de partes incorrecto.");
            }

            const filas = Array.isArray(partes.partes)
                ? partes.partes
                : Array.isArray(partes)
                    ? partes
                    : null;

            if (!filas || filas.length === 0) {
                throw new Error("No existen partes registradas.");
            }

            contenedor.replaceChildren();

            filas.forEach((parte, index) => {

                const elemento = document.createElement("div");

                elemento.className =
                    "border-bottom py-2";

                const nombre = [
                    parte.nombre,
                    parte.apellidoPaterno,
                    parte.apellidoMaterno
                ].filter(Boolean).join(" ");

                const titulo = document.createElement("strong");

                titulo.textContent =
                    `Parte ${index + 1}: ${nombre || "Sin nombre"}`;

                const detalle = document.createElement("p");

                detalle.className = "text-muted mb-1";

                detalle.textContent =
                    `Tipo: ${parte.tipo || parte.tipoParte || "No especificado"}`;

                elemento.append(titulo, detalle);
                contenedor.appendChild(elemento);

            });

            return true;

        } catch (error) {

            console.error("Error recuperando partes:", error);

            contenedor.textContent =
                "Los datos de la Parte 1 no tienen un formato válido.";

            mostrarErrorParte1();

            return false;
        }
    }

    function mostrarErrorParte1() {

        form.querySelectorAll("input, select, textarea, button")
            .forEach(elemento => {
                elemento.disabled = true;
            });

        const mensaje = document.createElement("p");

        mensaje.className = "text-danger mt-2";

        mensaje.textContent =
            "Regresa a la Parte 1 y valida nuevamente los datos.";

        document.getElementById("resumen-partes")
            .appendChild(mensaje);
    }

    /*
     * Consulta de mesas disponibles.
     */
    async function consultarMesas(idMateria) {

        panelMesa.hidden = false;

        mesaSeleccionada = null;

        mesaAsignada.textContent = "Consultando mesas disponibles...";

        try {

            const respuesta = await fetch(
                `${API}?idMateria=${encodeURIComponent(idMateria)}`,
                {
                    method: "GET",
                    credentials: "same-origin",
                    headers: {
                        "Accept": "application/json"
                    }
                }
            );

            const datos = await respuesta.json();

            if (!respuesta.ok || !datos.ok) {
                throw new Error(
                    datos.msg || datos.mensaje ||
                    "No fue posible consultar las mesas."
                );
            }

            if (!datos.mesaAsignada) {

                mesaAsignada.textContent =
                    "No existen mesas disponibles para esta materia.";

                return;
            }

            mesaSeleccionada = datos.mesaAsignada;

            mostrarMesaAsignada(mesaSeleccionada);

        } catch (error) {

            console.error("Error consultando mesas:", error);

            mesaAsignada.textContent = error.message;

            mesaSeleccionada = null;
        }
    }

    /*
     * Mostrar mesa sugerida.
     */
    function mostrarMesaAsignada(mesa) {

        mesaAsignada.replaceChildren();

        const nombre = document.createElement("h5");

        nombre.className = "text-primary fw-bold";

        nombre.textContent = mesa.nombreMesa;

        const carga = document.createElement("p");

        carga.className = "mb-1";

        carga.textContent =
            `Carga actual: ${mesa.cargaActual} expedientes`;

        const estado = document.createElement("span");

        estado.className = "badge text-bg-success";

        estado.textContent = "Disponible";

        mesaAsignada.append(nombre, carga, estado);
    }

    /*
     * Mostrar tabla de expedientes del juzgado.
     */
    async function cargarExpedientes() {

        tablaMesas.replaceChildren();

        const cargando = document.createElement("tr");
        const celda = document.createElement("td");

        celda.colSpan = 7;
        celda.className = "text-center text-muted";
        celda.textContent = "Cargando información...";

        cargando.appendChild(celda);
        tablaMesas.appendChild(cargando);

        try {

            const respuesta = await fetch(
                `${API}?accion=expedientes`,
                {
                    credentials: "same-origin",
                    headers: {
                        "Accept": "application/json"
                    }
                }
            );

            const datos = await respuesta.json();

            if (!respuesta.ok || !datos.ok) {
                throw new Error(
                    datos.msg || "Error al consultar expedientes."
                );
            }

            tablaMesas.replaceChildren();

            const expedientes = datos.expedientes || [];

            if (expedientes.length === 0) {

                const fila = document.createElement("tr");
                const columna = document.createElement("td");

                columna.colSpan = 7;
                columna.className = "text-center text-muted";
                columna.textContent = "No hay expedientes registrados.";

                fila.appendChild(columna);
                tablaMesas.appendChild(fila);

                return;
            }

            expedientes.forEach(expediente => {

                const fila = document.createElement("tr");

                const valores = [
                    expediente.folio,
                    expediente.nombreMesa,
                    expediente.idMateria,
                    expediente.cargaActual,
                    expediente.direccion,
                    expediente.fecha
                ];

                valores.forEach(valor => {

                    const columna = document.createElement("td");

                    columna.textContent =
                        valor == null ? "" : String(valor);

                    fila.appendChild(columna);
                });

                const acciones = document.createElement("td");

                const boton = document.createElement("button");

                boton.type = "button";
                boton.className = "btn-icon-action";

                boton.innerHTML =
                    '<i class="bi bi-eye"></i>';

                boton.title = "Consultar expediente";

                boton.setAttribute("aria-label",
                    "Consultar expediente");

                boton.addEventListener("click", () => {

                    alert(
                        `Folio: ${expediente.folio}\n` +
                        `Mesa: ${expediente.nombreMesa}\n` +
                        `Materia: ${expediente.idMateria}`
                    );

                });

                acciones.appendChild(boton);

                fila.appendChild(acciones);

                tablaMesas.appendChild(fila);

            });

        } catch (error) {

            console.error(error);

            tablaMesas.replaceChildren();

            const fila = document.createElement("tr");
            const columna = document.createElement("td");

            columna.colSpan = 7;
            columna.className = "text-danger text-center";
            columna.textContent = error.message;

            fila.appendChild(columna);
            tablaMesas.appendChild(fila);
        }
    }

    /*
     * Cambio de materia.
     */
    materia.addEventListener("change", () => {

        const idMateria = materia.value;

        if (!idMateria) {

            panelMesa.hidden = true;
            mesaSeleccionada = null;

            return;
        }

        consultarMesas(idMateria);
    });

    /*
     * Preparar modal de confirmación.
     */
    form.addEventListener("submit", event => {

        event.preventDefault();

        if (!form.reportValidity()) {
            return;
        }

        if (!partes) {
            alert("Debes validar primero los datos de la Parte 1.");
            return;
        }

        if (!mesaSeleccionada) {
            alert("No hay una mesa disponible para esta materia.");
            return;
        }

        const contenido =
            document.getElementById("confirmacion-contenido");

        contenido.replaceChildren();

        const datos = [
            ["Dirección", direccion.value],
            ["Materia", materia.options[materia.selectedIndex].text],
            ["Mesa asignada", mesaSeleccionada.nombreMesa],
            ["Carga actual", mesaSeleccionada.cargaActual],
            ["Descripción de hechos", descripcion.value]
        ];

        datos.forEach(([titulo, valor]) => {

            const bloque = document.createElement("div");

            bloque.className = "mb-3";

            const etiqueta = document.createElement("strong");
            etiqueta.textContent = titulo + ":";

            const texto = document.createElement("p");
            texto.className = "mb-0 text-muted";
            texto.textContent = String(valor);

            bloque.append(etiqueta, texto);

            contenido.appendChild(bloque);
        });

        modalConfirmacion.show();
    });

    /*
     * Guardar expediente.
     */
    btnGuardar.addEventListener("click", async () => {

        if (enviando) {
            return;
        }

        if (!mesaSeleccionada || !partes) {
            alert("Faltan datos para registrar el oficio.");
            return;
        }

        enviando = true;

        btnGuardar.disabled = true;

        btnGuardar.textContent = "Registrando...";

        try {

            const parametros = new URLSearchParams();

            parametros.append("idMateria", materia.value);
            parametros.append("direccion", direccion.value.trim());
            parametros.append("descripcion", descripcion.value.trim());

            parametros.append(
                "partesJson",
                JSON.stringify(partes)
            );

            const respuesta = await fetch(API, {

                method: "POST",

                credentials: "same-origin",

                headers: {
                    "Content-Type":
                        "application/x-www-form-urlencoded;charset=UTF-8",

                    "Accept": "application/json"
                },

                body: parametros.toString()

            });

            const datos = await respuesta.json();

            if (!respuesta.ok || !datos.ok) {

                throw new Error(
                    datos.msg ||
                    datos.mensaje ||
                    "No fue posible registrar el expediente."
                );
            }

            folioRegistrado = datos.folio;

            modalConfirmacion.hide();

            document.getElementById("folio-generado")
                .textContent = folioRegistrado;

            modalExito.show();

            await cargarExpedientes();

        } catch (error) {

            console.error("Error guardando oficio:", error);

            alert(error.message);

        } finally {

            enviando = false;

            btnGuardar.disabled = false;

            btnGuardar.innerHTML =
                '<i class="bi bi-save me-1"></i> Confirmar y guardar';
        }
    });

    /*
     * Ir a anexos y conservar el folio.
     */
    document.getElementById("btn-ir-anexos")
        .addEventListener("click", () => {

            if (!folioRegistrado) {
                return;
            }

            window.location.href =
                `anexos.html?folio=${encodeURIComponent(folioRegistrado)}`;
        });

    /*
     * Actualizar tabla.
     */
    btnActualizar.addEventListener("click", cargarExpedientes);

    /*
     * Inicialización.
     */
    if (recuperarPartes()) {
        cargarExpedientes();
    }

});
