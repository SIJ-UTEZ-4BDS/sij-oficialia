document.addEventListener("DOMContentLoaded", function () {
    const inputNombre = document.getElementById("input-nombre-parte");
    const selectMateria = document.getElementById("select-materia");
    const panelCoincidencias = document.getElementById("panel-coincidencias-tiempo-real");
    const formAgregarParte = document.getElementById("form-agregar-parte");
    const tablaPartesBody = document.getElementById("tabla-partes-body");

    let debounceTimer = null;
    const listaPartes = [];

    // Búsqueda con debounce de 300ms al soltar la tecla
    if (inputNombre) {
        inputNombre.addEventListener("input", function () {
            clearTimeout(debounceTimer);
            debounceTimer = setTimeout(() => {
                buscarDuplicidad();
            }, 300);
        });
    }

    if (selectMateria) {
        selectMateria.addEventListener("change", function () {
            buscarDuplicidad();
        });
    }

    function buscarDuplicidad() {
        const termino = inputNombre ? inputNombre.value.trim() : "";
        const idMateria = selectMateria ? selectMateria.value : "";

        if (termino.length < 2) {
            if (panelCoincidencias) panelCoincidencias.innerHTML = "";
            return;
        }

        const url = `../../api/duplicidad?q=${encodeURIComponent(termino)}&idMateria=${encodeURIComponent(idMateria)}`;

        fetch(url)
            .then(response => response.json())
            .then(data => {
                renderizarCoincidencias(data);
            })
            .catch(error => {
                console.error("Error al consultar duplicidad:", error);
            });
    }

    function renderizarCoincidencias(coincidencias) {
        if (!panelCoincidencias) return;

        if (!coincidencias || coincidencias.length === 0) {
            panelCoincidencias.innerHTML = `
                <div class="alert alert-success d-flex align-items-center mb-3" role="alert">
                    <i class="bi bi-check-circle-fill me-2"></i>
                    <div>Sin coincidencias en otros juzgados. Nombre disponible para registro.</div>
                </div>`;
            return;
        }

        let html = `
            <div class="alert alert-warning mb-3">
                <div class="d-flex align-items-center mb-2">
                    <i class="bi bi-exclamation-triangle-fill me-2 fs-5"></i>
                    <strong>Atención: Se encontraron coincidencias en otros juzgados (${coincidencias.length})</strong>
                </div>
                <div class="table-responsive">
                    <table class="table table-sm table-bordered bg-white mb-0">
                        <thead>
                            <tr class="table-secondary">
                                <th>Folio Expediente</th>
                                <th>Nombre Registrado</th>
                                <th>Rol</th>
                                <th>Materia</th>
                                <th>Juzgado</th>
                                <th class="text-center">Acción</th>
                            </tr>
                        </thead>
                        <tbody>`;

        coincidencias.forEach(item => {
            html += `
                <tr>
                    <td><strong>${item.folioExpediente || 'N/A'}</strong></td>
                    <td>${item.nombreCompleto}</td>
                    <td><span class="badge bg-secondary">${item.rolParte}</span></td>
                    <td>${item.nombreMateria || 'N/A'}</td>
                    <td>${item.nombreJuzgado || 'N/A'}</td>
                    <td class="text-center">
                        <button type="button" class="btn btn-sm btn-outline-info btn-icon-action" title="Ver coincidencia">
                            <i class="bi bi-eye"></i>
                        </button>
                    </td>
                </tr>`;
        });

        html += `
                        </tbody>
                    </table>
                </div>
            </div>`;

        panelCoincidencias.innerHTML = html;
    }

    // Registro dinámico de partes
    if (formAgregarParte) {
        formAgregarParte.addEventListener("submit", function (e) {
            e.preventDefault();
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
            } else {
                const selectRol = document.getElementById("select-rol");
                rol = selectRol ? selectRol.value : "";
            }

            if (!rol) {
                alert("Seleccione al menos un rol: DEMANDANTE, DEMANDADO o AMBOS.");
                return;
            }

            listaPartes.push({ nombreCompleto: nombre, rolParte: rol });
            renderizarTablaPartes();
            formAgregarParte.reset();
            if (panelCoincidencias) panelCoincidencias.innerHTML = "";
        });
    }

    function renderizarTablaPartes() {
        if (!tablaPartesBody) return;
        tablaPartesBody.innerHTML = "";

        listaPartes.forEach((parte, index) => {
            const tr = document.createElement("tr");
            tr.innerHTML = `
                <td>${index + 1}</td>
                <td>${parte.nombreCompleto}</td>
                <td><span class="badge bg-primary">${parte.rolParte}</span></td>
                <td class="text-center">
                    <button type="button" class="btn btn-sm btn-outline-danger btn-icon-action" onclick="eliminarParte(${index})">
                        <i class="bi bi-trash"></i>
                    </button>
                </td>
            `;
            tablaPartesBody.appendChild(tr);
        });
    }

    window.eliminarParte = function (index) {
        listaPartes.splice(index, 1);
        renderizarTablaPartes();
    };
});