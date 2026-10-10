/*
 * Add/remove condition rows on the Compare page.
 *
 * Scope is deliberately small: the server owns all query logic, this only
 * clones and removes DOM nodes so the user can enter N conditions without a
 * page reload between each. Submission is a standard GET form.
 */
(function () {
    function updateRemoveButtons() {
        var rows = document.querySelectorAll('.condition-row');
        rows.forEach(function (row) {
            var btn = row.querySelector('.remove-condition');
            if (btn) {
                var disabled = rows.length <= 1;
                btn.disabled = disabled;
                btn.style.opacity = disabled ? '0.3' : '1';
                btn.style.cursor = disabled ? 'not-allowed' : 'pointer';
            }
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        var container = document.getElementById('conditions');
        var addBtn = document.getElementById('add-condition');

        if (!container || !addBtn) return;

        addBtn.addEventListener('click', function () {
            var first = container.querySelector('.condition-row');
            if (!first) return;

            var clone = first.cloneNode(true);
            clone.querySelectorAll('input').forEach(function (el) { el.value = ''; });
            clone.querySelectorAll('select').forEach(function (el) { el.selectedIndex = 0; });
            container.appendChild(clone);
            updateRemoveButtons();
        });

        container.addEventListener('click', function (e) {
            if (e.target.classList.contains('remove-condition')) {
                var rows = container.querySelectorAll('.condition-row');
                if (rows.length > 1) {
                    e.target.closest('.condition-row').remove();
                    updateRemoveButtons();
                }
            }
        });

        updateRemoveButtons();
    });
})();