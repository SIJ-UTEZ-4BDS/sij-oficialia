document.addEventListener("DOMContentLoaded", function () {
    const inputNombre = document.getElementById("input-nombre-parte");
    const selectMateria = document.getElementById("select-materia");
    const panelCoincidencias = document.getElementById("panel-coincidencias-tiempo-real");
    const formAgregarParte = document.getElementById("form-agregar-parte");
    const tablaPartesBody = document.getElementById("tabla-partes-body");

    let debounceTimer = null;
    let consultaActual = 0;
    const listaPartes = [];

    cargarSesion();

    if (inputNombre) {
        inputNombre.addEventListener("input", function () {
            clearTimeout(debounceTimer);
            debounceTimer = setTimeout(buscarDuplicidad, 300);
        });
    }

    if (selectMateria) {
        selectMateria.addEventListener("change", buscarDuplicidad);
    }

    async function cargarSesion() {
        const userBadge = document.getElementById("user-badge");
        const userJuzgado = document.getElementById("user-juzgado");
        try {
            const response = await fetch("../../api/auth/session");
            if (!response.ok) {
                window.location.href = "../../index.html";
                return;
            }

            const session = await response.json();
            if (userBadge) {
                userBadge.textContent = `${session.nombreCompleto} • ${session.claveEmpleado}`;
            }
            if (userJuzgado) {
                userJuzgado.textContent = `| ${session.nombreJuzgado}`;
            }
            if (session.rol && session.rol.startsWith("ADMIN_")) {
                document.getElementById("link-volver-admin")?.classList.remove("d-none");
            }
        } catch (error) {
            console.error("Error al verificar la sesión:", error);
            if (userBadge) {
                userBadge.textContent = "No fue posible verificar la sesión";
            }
        }
    }

    async function buscarDuplicidad() {
        const termino = inputNombre ? inputNombre.value.trim() : "";
        const idMateria = selectMateria ? selectMateria.value : "";
        const consulta = ++consultaActual;

        if (termino.length < 2) {
            if (panelCoincidencias) {
                panelCoincidencias.innerHTML = `
                    <div class="alert alert-light border small mb-0" role="status">
                        Capture al menos dos caracteres del nombre para iniciar la consulta automática.
                    </div>`;
            }
            return;
        }

        if (panelCoincidencias) {
            panelCoincidencias.innerHTML = `
                <div class="alert alert-light border small mb-0" role="status">
                    <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
                    Consultando posibles coincidencias...
                </div>`;
        }

        const url = `../../api/duplicidad?q=${encodeURIComponent(termino)}&idMateria=${encodeURIComponent(idMateria)}`;
        try {
            const response = await fetch(url);
            if (!response.ok) {
                throw new Error(`La consulta respondió con estado ${response.status}`);
            }
            const coincidencias = await response.json();
            if (consulta === consultaActual) {
                renderizarCoincidencias(coincidencias);
            }
        } catch (error) {
            if (consulta === consultaActual) {
                console.error("Error al consultar duplicidad:", error);
                if (panelCoincidencias) {
                    panelCoincidencias.innerHTML = `
                        <div class="alert alert-danger small mb-0" role="alert">
                            No fue posible consultar las coincidencias. Intente nuevamente.
                        </div>`;
                }
            }
        }
    }

    function renderizarCoincidencias(coincidencias) {
        if (!panelCoincidencias) return;

        if (!Array.isArray(coincidencias) || coincidencias.length === 0) {
            panelCoincidencias.innerHTML = `
                <div class="alert alert-success small mb-0" role="status">
                    <i class="bi bi-check-circle-fill me-2"></i>
                    Sin coincidencias en otros juzgados. Nombre disponible para registro.
                </div>`;
            return;
        }

        const encabezado = document.createElement("div");
        encabezado.className = "alert alert-warning small mb-0";
        encabezado.setAttribute("role", "status");
        encabezado.innerHTML = `
            <div class="d-flex align-items-center mb-3">
                <i class="bi bi-exclamation-triangle-fill me-2 fs-5"></i>
                <strong>Se encontraron coincidencias en otros juzgados (${coincidencias.length})</strong>
            </div>`;

        const contenedor = document.createElement("div");
        contenedor.className = "table-responsive";
        const tabla = document.createElement("table");
        tabla.className = "table table-sm align-middle mb-0";
        tabla.innerHTML = `
            <thead>
                <tr>
                    <th scope="col">Expediente</th>
                    <th scope="col">Nombre registrado</th>
                    <th scope="col">Carácter</th>
                    <th scope="col">Materia</th>
                    <th scope="col">Juzgado</th>
                    <th scope="col" class="text-center">Acciones</th>
                </tr>
            </thead>`;

        const cuerpo = document.createElement("tbody");
        coincidencias.forEach(item => {
            const fila = document.createElement("tr");
            [
                item.folioExpediente || "N/A",
                item.nombreCompleto || "N/A",
                item.rolParte || "N/A",
                item.nombreMateria || "N/A",
                item.nombreJuzgado || "N/A"
            ].forEach(valor => {
                const celda = document.createElement("td");
                celda.textContent = valor;
                fila.appendChild(celda);
            });

            const acciones = document.createElement("td");
            acciones.className = "text-center";
            const boton = document.createElement("button");
            boton.type = "button";
            boton.className = "btn-icon-action icon-dorado";
            boton.title = "Ver coincidencia";
            boton.setAttribute("aria-label", "Ver coincidencia");
            boton.innerHTML = '<i class="bi bi-eye"></i>';
            acciones.appendChild(boton);
            fila.appendChild(acciones);
            cuerpo.appendChild(fila);
        });

        tabla.appendChild(cuerpo);
        contenedor.appendChild(tabla);
        encabezado.appendChild(contenedor);
        panelCoincidencias.replaceChildren(encabezado);
    }

    if (formAgregarParte) {
        formAgregarParte.addEventListener("submit", function (event) {
            event.preventDefault();
            const nombre = inputNombre ? inputNombre.value.trim() : "";
            const checkDemandante = document.getElementById("check-demandante")?.checked;
            const checkDemandado = document.getElementById("check-demandado")?.checked;

            if (!nombre) {
                alert("El nombre de la persona es obligatorio.");
                return;
            }

            let rol = "";
            if (checkDemandante && checkDemandado) {
                rol = "AMBOS";
            } else if (checkDemandante) {
                rol = "DEMANDANTE";
            } else if (checkDemandado) {
                rol = "DEMANDADO";
            }

            if (!rol) {
                alert("Seleccione Demandante, Demandado o ambos caracteres.");
                return;
            }

            listaPartes.push({ nombreCompleto: nombre, rolParte: rol });
            renderizarTablaPartes();
            formAgregarParte.reset();
            if (panelCoincidencias) {
                panelCoincidencias.innerHTML = `
                    <div class="alert alert-light border small mb-0" role="status">
                        Capture al menos dos caracteres del nombre para iniciar la consulta automática.
                    </div>`;
            }
        });
    }

    function renderizarTablaPartes() {
        if (!tablaPartesBody) return;
        tablaPartesBody.replaceChildren();

        if (listaPartes.length === 0) {
            tablaPartesBody.innerHTML = `
                <tr><td colspan="4" class="text-center py-3">No hay partes agregadas aún.</td></tr>`;
            return;
        }

        listaPartes.forEach((parte, index) => {
            const fila = document.createElement("tr");
            const numero = document.createElement("td");
            numero.textContent = String(index + 1);
            const nombre = document.createElement("td");
            nombre.textContent = parte.nombreCompleto;
            const caracter = document.createElement("td");
            const insignia = document.createElement("span");
            insignia.className = "badge badge-rol-global";
            insignia.textContent = parte.rolParte;
            caracter.appendChild(insignia);

            const acciones = document.createElement("td");
            acciones.className = "text-center";
            const eliminar = document.createElement("button");
            eliminar.type = "button";
            eliminar.className = "btn-icon-action";
            eliminar.title = "Eliminar parte";
            eliminar.setAttribute("aria-label", "Eliminar parte");
            eliminar.innerHTML = '<i class="bi bi-trash"></i>';
            eliminar.addEventListener("click", () => {
                listaPartes.splice(index, 1);
                renderizarTablaPartes();
            });
            acciones.appendChild(eliminar);

            fila.append(numero, nombre, caracter, acciones);
            tablaPartesBody.appendChild(fila);
        });
    }
});
