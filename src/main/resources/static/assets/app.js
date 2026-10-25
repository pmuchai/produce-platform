'use strict';

(() => {
  const base = document.querySelector('meta[name="app-base"]').content;
  const csrfToken = document.querySelector('meta[name="csrf-token"]').content;
  const csrfHeader = document.querySelector('meta[name="csrf-header"]').content;
  const notice = document.getElementById('notice');
  const showNotice = (message, error = false) => {
    if (!notice) return;
    notice.textContent = message;
    notice.className = error ? 'notice error' : 'notice';
    notice.hidden = false;
  };
  if (new URLSearchParams(window.location.search).get('saved') === '1') {
    showNotice(window.location.pathname.includes('/ui/stock') ? 'Stock changes posted.' : 'Catalogue changes saved.');
  }

  document.querySelectorAll('.date-filter').forEach(form => {
    const start = form.elements.namedItem('start');
    const end = form.elements.namedItem('end');
    form.addEventListener('input', () => end.setCustomValidity(''));
    form.addEventListener('submit', event => {
      const days = (Date.parse(end.value) - Date.parse(start.value)) / 86400000;
      end.setCustomValidity(days < 0 ? 'End date must be on or after start date.'
        : days >= 366 ? 'Choose an interval of at most 366 days.' : '');
      if (!form.checkValidity()) {
        event.preventDefault();
        form.reportValidity();
      }
    });
  });

  const request = async (path, options = {}) => {
    const response = await fetch(base + path, {
      ...options,
      headers: {Accept: 'application/json', [csrfHeader]: csrfToken, ...options.headers}
    });
    if (!response.ok) {
      if (response.status === 401) {
        throw new Error('Your session has expired or access changed. Sign in again to continue.');
      }
      if (response.headers.get('Content-Type')?.includes('json')) {
        const problem = await response.json();
        const details = (problem.errors || []).map(error => `${error.field}: ${error.message}`).join('\n');
        throw new Error(details || problem.detail || `Request failed (${response.status}).`);
      }
      throw new Error(`Request failed (${response.status}). Please retry or contact your administrator.`);
    }
    return response.json();
  };

  const receiptLines = document.getElementById('receipt-lines');
  document.getElementById('add-receipt-line')?.addEventListener('click', () => {
    if (receiptLines.children.length >= 50) {
      showNotice('A receipt can contain at most 50 product lines.', true);
      return;
    }
    const line = receiptLines.firstElementChild.cloneNode(true);
    line.querySelectorAll('input, select').forEach(field => { field.value = ''; });
    receiptLines.append(line);
  });
  receiptLines?.addEventListener('click', event => {
    if (!event.target.closest('[data-remove-line]')) return;
    if (receiptLines.children.length === 1) {
      showNotice('A receipt needs at least one product line.', true);
      return;
    }
    event.target.closest('.receipt-line').remove();
  });
  document.querySelectorAll('[data-batch-branch]').forEach(select => {
    select.addEventListener('change', () => {
      select.form.elements.namedItem('branchId').value = select.selectedOptions[0]?.dataset.branch || '';
    });
  });
  document.querySelectorAll('form[data-stock]').forEach(form => {
    const message = form.querySelector('.form-message');
    form.addEventListener('submit', async event => {
      event.preventDefault();
      const resource = form.dataset.stock;
      const data = {};
      for (const field of form.elements) {
        if (!field.name || field.closest('.receipt-line')) continue;
        data[field.name] = field.value === '' ? null : field.value;
      }
      if (resource === 'receipts') {
        data.lines = [...form.querySelectorAll('.receipt-line')].map(line => {
          const values = {};
          line.querySelectorAll('[name]').forEach(field => { values[field.name] = field.value || null; });
          return values;
        });
      }
      if (resource !== 'thresholds') {
        form.dataset.requestId ||= crypto.randomUUID();
        data.requestId = form.dataset.requestId;
      }
      const submit = form.querySelector('[type="submit"]');
      submit.disabled = true;
      message.className = 'form-message';
      message.textContent = 'Posting...';
      try {
        await request(`api/inventory/${resource}`, {
          method: resource === 'thresholds' ? 'PUT' : 'POST',
          headers: {'Content-Type': 'application/json'}, body: JSON.stringify(data)
        });
        const target = new URL(window.location.href);
        target.searchParams.set('saved', '1');
        target.hash = resource === 'receipts' ? 'stock-batches' : resource === 'thresholds' ? 'stock-balances' : 'stock-history';
        window.history.replaceState(null, '', target);
        window.location.reload();
      } catch (error) {
        message.className = 'form-message error';
        message.textContent = error instanceof TypeError ? 'Network error. Retry without clearing the form to avoid duplicate posting.' : error.message;
      } finally {
        submit.disabled = false;
      }
    });
    form.addEventListener('reset', () => {
      delete form.dataset.requestId;
      message.textContent = '';
      message.className = 'form-message';
      if (form.dataset.stock === 'thresholds') {
        form.elements.namedItem('expectedVersion').value = '-1';
      }
      if (form.dataset.stock === 'receipts') {
        while (receiptLines.children.length > 1) receiptLines.lastElementChild.remove();
      }
    });
  });
  document.querySelectorAll('[data-reverse]').forEach(button => {
    button.addEventListener('click', () => {
      const form = document.querySelector('form[data-stock="reversals"]');
      form.reset();
      form.elements.namedItem('branchId').value = button.dataset.branch;
      form.elements.namedItem('movementId').value = button.dataset.id;
      form.scrollIntoView({behavior: 'smooth', block: 'center'});
      form.elements.namedItem('reason').focus({preventScroll: true});
    });
  });
  document.querySelectorAll('[data-threshold]').forEach(button => {
    button.addEventListener('click', () => {
      const form = document.querySelector('form[data-stock="thresholds"]');
      form.reset();
      form.elements.namedItem('branchId').value = button.dataset.branch;
      form.elements.namedItem('productId').value = button.dataset.product;
      form.elements.namedItem('minimumQuantity').value = button.dataset.minimum || '';
      form.elements.namedItem('expectedVersion').value = button.dataset.version;
      form.scrollIntoView({behavior: 'smooth', block: 'center'});
      form.elements.namedItem('minimumQuantity').focus({preventScroll: true});
    });
  });

  document.querySelectorAll('form[data-resource]').forEach(form => {
    const resource = form.dataset.resource;
    const message = form.querySelector('.form-message');
    form.addEventListener('submit', async event => {
      event.preventDefault();
      const submit = form.querySelector('[type="submit"]');
      const data = {};
      for (const field of form.elements) {
        if (!field.name || field.name === 'id' || field.name === 'version') continue;
        data[field.name] = field.type === 'checkbox' ? field.checked
          : field.hasAttribute('data-nullable') && field.value === '' ? null : field.value;
      }
      const id = form.elements.namedItem('id')?.value;
      const version = form.elements.namedItem('version')?.value;
      const method = resource === 'packaging' || id ? 'PUT' : 'POST';
      submit.disabled = true;
      message.className = 'form-message';
      message.textContent = 'Working...';
      try {
        const result = await request(`api/catalogue/${resource}${id ? '/' + encodeURIComponent(id) : ''}`, {
          method, headers: {'Content-Type': 'application/json', ...(version ? {'If-Match': `"${version}"`} : {})},
          body: JSON.stringify(data)
        });
        if (resource === 'convert') {
          message.textContent = `${result.quantity} ${result.unitCode}`;
        } else {
          const target = new URL(window.location.href);
          target.searchParams.set('saved', '1');
          target.hash = resource;
          // Fragment-only navigation would leave tables stale after consecutive saves.
          window.history.replaceState(null, '', target);
          window.location.reload();
        }
      } catch (error) {
        message.className = 'form-message error';
        message.textContent = error instanceof TypeError ? 'Network error. Check your connection and retry.' : error.message;
      } finally {
        submit.disabled = false;
      }
    });
    form.addEventListener('reset', () => {
      const id = form.elements.namedItem('id');
      if (id) id.value = '';
      const version = form.elements.namedItem('version');
      if (version) version.value = '';
      message.textContent = '';
      message.className = 'form-message';
    });
  });

  document.querySelectorAll('[data-edit]').forEach(button => {
    button.addEventListener('click', async () => {
      button.disabled = true;
      try {
        const snapshot = await request('api/catalogue');
        const resource = button.dataset.edit;
        const item = snapshot[resource].find(record => record.id === button.dataset.id);
        if (!item) throw new Error('Record no longer exists. Reload the catalogue.');
        const form = document.querySelector(`form[data-resource="${resource}"]`);
        form.reset();
        for (const [name, value] of Object.entries(item)) {
          const field = form.elements.namedItem(name);
          if (!field) continue;
          if (field.type === 'checkbox') field.checked = value;
          else field.value = value ?? '';
        }
        const editor = document.getElementById(`${resource}-editor`);
        editor.open = true;
        editor.scrollIntoView({behavior: 'smooth', block: 'center'});
        form.querySelector('input:not([type="hidden"]), select').focus({preventScroll: true});
      } catch (error) {
        showNotice(error instanceof TypeError ? 'Network error while loading the record.' : error.message, true);
      } finally {
        button.disabled = false;
      }
    });
  });

  document.getElementById('catalogue-search')?.addEventListener('input', event => {
    const term = event.target.value.toLocaleLowerCase().trim();
    document.querySelectorAll('[data-row]').forEach(row => {
      row.hidden = !row.textContent.toLocaleLowerCase().includes(term);
    });
  });
})();
