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
    showNotice(window.location.pathname.includes('/ui/stock') ? 'Stock changes posted.'
      : window.location.pathname.endsWith('/ui/expenses') ? 'Expense change recorded.'
      : window.location.pathname.endsWith('/ui/users') ? 'Staff approval and branch assignments saved.'
      : window.location.pathname.endsWith('/ui/branches') ? 'Branch changes saved.' : 'Catalogue changes saved.');
  }

  document.querySelectorAll('.date-filter').forEach(form => {
    const start = form.elements.namedItem('start');
    const end = form.elements.namedItem('end');
    if (!start || !end) return;
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
      const sessionExpired = response.status === 401 || (response.status === 403
        && (await fetch(base + 'api/auth/me', {headers: {Accept: 'application/json'}})).status === 401);
      if (sessionExpired) {
        window.location.replace(base + 'login');
        // Keep callers from showing errors or retrying a mutation during sign-in navigation.
        return new Promise(() => {});
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

  const newRequestId = () => {
    const crypto = globalThis.crypto;
    if (typeof crypto?.randomUUID === 'function') return crypto.randomUUID();
    if (typeof crypto?.getRandomValues !== 'function') {
      throw new Error('Secure request identifiers are unavailable. Use a browser with Web Crypto support.');
    }
    // randomUUID requires HTTPS or localhost; getRandomValues also works on LAN HTTP.
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = [...bytes].map(byte => byte.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  };

  const whatsappCreate = document.getElementById('whatsapp-create');
  const whatsappConfirm = document.getElementById('whatsapp-confirm');
  const whatsappUnlink = document.getElementById('whatsapp-unlink');
  [whatsappCreate, whatsappConfirm, whatsappUnlink].filter(Boolean).forEach(button => {
    button.addEventListener('click', async () => {
      button.disabled = true;
      try {
        if (button === whatsappCreate) {
          const created = await request('api/whatsapp/link/challenges', {method: 'POST'});
          document.getElementById('whatsapp-command').textContent = `LINK ${created.code}`;
          document.getElementById('whatsapp-open').href = created.whatsappUrl;
          document.getElementById('whatsapp-code').hidden = false;
          whatsappConfirm?.remove();
          showNotice('Send the code from WhatsApp, then refresh this page to verify and confirm your number.');
        } else {
          await request(button === whatsappConfirm ? 'api/whatsapp/link/confirm' : 'api/whatsapp/link', {
            method: button === whatsappConfirm ? 'POST' : 'DELETE',
            ...(button === whatsappConfirm ? {headers: {'Content-Type': 'application/json'},
              body: JSON.stringify({challengeId: button.dataset.challengeId})} : {})
          });
          window.location.assign(base + 'ui/whatsapp');
        }
      } catch (error) {
        showNotice(error.message, true);
      } finally {
        button.disabled = false;
      }
    });
  });

  const staffForm = document.getElementById('staff-form');
  const expenseForm = document.getElementById('expense-form');
  if (expenseForm) {
    const updateReference = () => {
      expenseForm.elements.paymentReference.required = expenseForm.elements.paymentMethod.value !== 'CASH';
    };
    expenseForm.elements.paymentMethod.addEventListener('change', updateReference);
    updateReference();
  }
  [expenseForm, ...document.querySelectorAll('.expense-void')].filter(Boolean).forEach(form => {
    let identity;
    let requestId;
    form.addEventListener('submit', async event => {
      event.preventDefault();
      const create = form === expenseForm;
      const payload = create ? {
        branchId: form.elements.branchId.value, paidDate: form.elements.paidDate.value,
        category: form.elements.category.value, description: form.elements.description.value,
        payee: form.elements.payee.value, amount: form.elements.amount.value,
        paymentMethod: form.elements.paymentMethod.value, paymentReference: form.elements.paymentReference.value
      } : {reason: form.elements.reason.value};
      const button = form.querySelector('button[type="submit"]');
      try {
        const nextIdentity = JSON.stringify(payload);
        if (identity !== nextIdentity) {
          requestId = newRequestId();
          identity = nextIdentity;
        }
        button.disabled = true;
        await request(create ? 'api/expenses' : `api/expenses/${form.dataset.expenseId}/void`, {
          method: 'POST', headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({...payload, requestId})
        });
        const destination = new URL(base + 'ui/expenses', window.location.origin);
        destination.search = create
          ? new URLSearchParams({branchId: payload.branchId, start: payload.paidDate, end: payload.paidDate}).toString()
          : window.location.search;
        destination.searchParams.set('saved', '1');
        window.location.assign(destination.href);
      } catch (error) {
        showNotice(error.message, true);
        button.disabled = false;
      }
    });
  });
  const transferDispatch = document.getElementById('transfer-dispatch');
  const transferReceive = document.getElementById('transfer-receive');
  if (transferDispatch) {
    const container = document.getElementById('transfer-lines');
    const prototype = container.firstElementChild.cloneNode(true);
    document.getElementById('add-transfer-line').addEventListener('click', () => {
      if (container.children.length < 100) container.append(prototype.cloneNode(true));
    });
    container.addEventListener('click', event => {
      if (event.target.closest('[data-remove-transfer-line]') && container.children.length > 1) {
        event.target.closest('.transfer-line').remove();
      }
    });
  }
  [transferDispatch, transferReceive].filter(Boolean).forEach(form => {
    let identity;
    let requestId;
    form.addEventListener('submit', async event => {
      event.preventDefault();
      const dispatch = form === transferDispatch;
      const payload = dispatch ? {
        sourceId: form.elements.sourceId.value, destinationId: form.elements.destinationId.value,
        notes: form.elements.notes.value,
        lines: [...form.querySelectorAll('.transfer-line')].map(row => ({
          productId: row.querySelector('[name="productId"]').value,
          quantity: row.querySelector('[name="quantity"]').value
        }))
      } : {
        notes: form.elements.notes.value,
        allocations: [...form.querySelectorAll('.transfer-arrival')].map(row => ({
          allocationId: row.dataset.allocationId,
          receivedQuantity: row.querySelector('[name="receivedQuantity"]').value,
          shortageQuantity: row.querySelector('[name="shortageQuantity"]').value,
          damagedQuantity: row.querySelector('[name="damagedQuantity"]').value,
          reason: row.querySelector('[name="reason"]').value
        }))
      };
      if (dispatch && payload.sourceId === payload.destinationId) {
        showNotice('Choose different source and destination branches.', true);
        return;
      }
      const button = form.querySelector('button[type="submit"]');
      try {
        const nextIdentity = JSON.stringify(payload);
        if (identity !== nextIdentity) {
          requestId = newRequestId();
          identity = nextIdentity;
        }
        button.disabled = true;
        const result = await request(dispatch ? 'api/transfers'
          : `api/transfers/${form.dataset.transferId}/receive`, {
          method: 'POST', headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({...payload, requestId})
        });
        window.location.assign(base + `ui/transfers/${result.transfer.id}`);
      } catch (error) {
        showNotice(error.message, true);
        button.disabled = false;
      }
    });
  });
  if (staffForm) {
    const branchSelect = staffForm.elements.branchIds;
    const updateScope = () => {
      const global = staffForm.elements.scope.value === 'global';
      const multiple = staffForm.elements.scope.value === 'multiple';
      const selected = [...branchSelect.selectedOptions].map(option => option.value).filter(Boolean);
      branchSelect.multiple = multiple;
      branchSelect.size = multiple ? 5 : 1;
      [...branchSelect.options].forEach(option => {
        option.hidden = multiple && !option.value;
        option.selected = selected.length > 1 && !multiple ? false : selected.includes(option.value);
      });
      if (!multiple && selected.length !== 1) branchSelect.value = '';
      branchSelect.disabled = global;
      branchSelect.required = !global && staffForm.elements.active.checked;
      document.getElementById('staff-branch-label').hidden = global;
    };
    staffForm.elements.scope.addEventListener('change', updateScope);
    staffForm.elements.active.addEventListener('change', updateScope);
    staffForm.addEventListener('reset', () => {
      staffForm.elements.accessRequestId.value = '';
      staffForm.elements.id.value = '';
      staffForm.elements.version.value = '';
      staffForm.elements.username.readOnly = false;
      staffForm.elements.subject.readOnly = false;
      staffForm.elements.active.disabled = true;
      document.getElementById('staff-editor-title').textContent = 'Approve staff / sales user';
      staffForm.querySelector('[type="submit"]').textContent = 'Approve staff';
      staffForm.querySelector('.form-message').textContent = '';
      setTimeout(updateScope, 0);
    });
    staffForm.addEventListener('submit', async event => {
      event.preventDefault();
      const id = staffForm.elements.id.value;
      const accessRequestId = staffForm.elements.accessRequestId.value;
      const global = staffForm.elements.scope.value === 'global';
      const data = {
        fullName: staffForm.elements.fullName.value.trim(),
        allBranches: global,
        branchIds: global ? [] : [...branchSelect.selectedOptions].map(option => option.value).filter(Boolean)
      };
      if (id) data.active = staffForm.elements.active.checked;
      else {
        data.username = staffForm.elements.username.value.trim();
        if (!accessRequestId) data.subject = staffForm.elements.subject.value.trim();
      }
      const submit = staffForm.querySelector('[type="submit"]');
      const message = staffForm.querySelector('.form-message');
      submit.disabled = true;
      message.className = 'form-message';
      message.textContent = 'Saving staff access...';
      try {
        const path = accessRequestId ? `api/users/access-requests/${encodeURIComponent(accessRequestId)}/approve`
          : `api/users${id ? '/' + encodeURIComponent(id) : ''}`;
        await request(path, {
          method: id ? 'PUT' : 'POST',
          headers: {'Content-Type': 'application/json', ...(id ? {'If-Match': `"${staffForm.elements.version.value}"`} : {})},
          body: JSON.stringify(data)
        });
        const target = new URL(window.location.href);
        target.searchParams.set('saved', '1');
        target.hash = 'staff-directory';
        window.history.replaceState(null, '', target);
        window.location.reload();
      } catch (error) {
        message.className = 'form-message error';
        message.textContent = error instanceof TypeError
          ? 'Network error. Reload the directory to check whether the change saved before retrying.' : error.message;
      } finally {
        submit.disabled = false;
      }
    });
    document.querySelectorAll('[data-review-access]').forEach(button => {
      button.addEventListener('click', async () => {
        button.disabled = true;
        try {
          const requests = await request('api/users/access-requests');
          const pending = requests.find(item => item.id === button.dataset.id);
          if (!pending) throw new Error('Request is no longer pending. Reload the directory.');
          staffForm.reset();
          staffForm.elements.accessRequestId.value = pending.id;
          staffForm.elements.username.value = pending.suggestedUsername;
          staffForm.elements.fullName.value = pending.fullName;
          staffForm.elements.subject.value = pending.subject;
          staffForm.elements.subject.readOnly = true;
          document.getElementById('staff-editor-title').textContent = 'Approve access request';
          staffForm.querySelector('[type="submit"]').textContent = 'Approve access';
          staffForm.scrollIntoView({behavior: 'smooth', block: 'center'});
          staffForm.elements.fullName.focus({preventScroll: true});
        } catch (error) {
          showNotice(error instanceof TypeError ? 'Network error while loading the access request.' : error.message, true);
        } finally {
          button.disabled = false;
        }
      });
    });
    document.querySelectorAll('[data-edit-user]').forEach(button => {
      button.addEventListener('click', async () => {
        button.disabled = true;
        try {
          const users = await request('api/users');
          const user = users.find(item => item.id === button.dataset.id);
          if (!user) throw new Error('Staff record no longer exists. Reload the directory.');
          staffForm.reset();
          for (const field of ['id', 'version', 'username', 'fullName', 'subject']) {
            staffForm.elements.namedItem(field).value = user[field];
          }
          staffForm.elements.username.readOnly = true;
          staffForm.elements.subject.readOnly = true;
          staffForm.elements.active.disabled = false;
          staffForm.elements.active.checked = user.active;
          staffForm.elements.scope.value = user.allBranches ? 'global' : user.branchIds.length === 1 ? 'single' : 'multiple';
          branchSelect.multiple = true;
          [...branchSelect.options].forEach(option => { option.selected = user.branchIds.includes(option.value); });
          updateScope();
          document.getElementById('staff-editor-title').textContent = `Edit staff: ${user.username}`;
          staffForm.querySelector('[type="submit"]').textContent = 'Save assignments';
          staffForm.scrollIntoView({behavior: 'smooth', block: 'center'});
          staffForm.elements.fullName.focus({preventScroll: true});
        } catch (error) {
          showNotice(error instanceof TypeError ? 'Network error while loading the staff record.' : error.message, true);
        } finally {
          button.disabled = false;
        }
      });
    });
  }

  const receiptLines = document.getElementById('receipt-lines');
  const saleForm = document.getElementById('sale-form');
  const saleLines = document.getElementById('sale-lines');
  const readSaleLines = () => [...saleLines.children].map(line => Object.fromEntries(
    [...line.querySelectorAll('[name]')].map(field => [field.name, field.value || null])));
  const readSaleQuote = () => ({lines: readSaleLines(), saleDate: saleForm.elements.saleDate.value});
  let quotedLines = null;
  let quoteTimer;
  const saleCount = () => {
    if (!saleForm) return;
    const count = [...saleLines.children].filter(line => line.querySelector('[name="productId"]').value).length;
    document.getElementById('sale-count').textContent = `${count} ${count === 1 ? 'item' : 'items'}`;
  };
  const invalidateQuote = () => {
    clearTimeout(quoteTimer);
    quotedLines = null;
    const output = document.getElementById('sale-total');
    output.className = '';
    output.textContent = 'Calculate the total';
    saleLines.querySelectorAll('.line-total').forEach(total => { total.textContent = '--'; });
    saleForm.elements.paymentConfirmed.checked = false;
    saleCount();
    quoteTimer = setTimeout(() => {
      if (!document.getElementById('quote-sale').disabled
          && [...saleLines.querySelectorAll('input, select'), saleForm.elements.saleDate].every(field => field.checkValidity())) {
        quoteSale();
      }
    }, 350);
  };
  saleLines?.addEventListener('input', invalidateQuote);
  saleLines?.addEventListener('change', event => {
    if (event.target.name === 'productId') {
      const line = event.target.closest('.sale-line');
      line.querySelector('[name="unitId"]').value = event.target.selectedOptions[0]?.dataset.unit || '';
      line.querySelector('[name="unitPrice"]').value = '';
      line.querySelector('[name="measuredBaseQuantity"]').value = '';
    }
    invalidateQuote();
  });
  saleForm?.elements.saleDate.addEventListener('input', invalidateQuote);
  const customer = document.getElementById('sale-customer');
  customer?.addEventListener('change', () => {
    const adding = customer.value === '__new__';
    document.getElementById('new-customer-label').hidden = !adding;
    saleForm.elements.customerName.disabled = !adding;
    saleForm.elements.customerName.required = adding;
  });
  const addSaleLine = () => {
    if (saleLines.children.length >= 50) {
      showNotice('A sale can contain at most 50 lines.', true);
      return;
    }
    const line = saleLines.firstElementChild.cloneNode(true);
    line.querySelectorAll('input, select').forEach(field => { field.value = ''; });
    line.querySelector('[name="quantity"]').value = '1';
    line.querySelectorAll('details').forEach(details => { details.open = false; });
    line.querySelector('.line-total').textContent = '--';
    saleLines.append(line);
    invalidateQuote();
    return line;
  };
  document.getElementById('add-sale-line')?.addEventListener('click', () => {
    addSaleLine()?.querySelector('[name="productId"]').focus();
  });
  const changeQuantity = (line, direction) => {
    const field = line.querySelector('[name="quantity"]');
    const value = field.value || '0';
    if (!/^\d*(?:\.\d{0,6})?$/.test(value)) {
      showNotice('Enter a decimal quantity with up to six decimal places.', true);
      return;
    }
    const [whole, fraction = ''] = value.split('.');
    const quantity = BigInt(whole || '0') * 1000000n + BigInt(fraction.padEnd(6, '0')) + BigInt(direction) * 1000000n;
    if (quantity <= 0n || quantity > 9999999999999999999n) return;
    field.value = `${quantity / 1000000n}.${(quantity % 1000000n).toString().padStart(6, '0')}`.replace(/\.?0+$/, '');
    invalidateQuote();
  };
  saleLines?.addEventListener('click', event => {
    const quantityButton = event.target.closest('[data-quantity-change]');
    if (quantityButton) {
      changeQuantity(quantityButton.closest('.sale-line'), quantityButton.dataset.quantityChange);
      return;
    }
    if (!event.target.closest('[data-remove-sale-line]')) return;
    if (saleLines.children.length === 1) {
      saleLines.firstElementChild.querySelectorAll('input, select').forEach(field => { field.value = ''; });
      saleLines.firstElementChild.querySelector('[name="quantity"]').value = '1';
    } else {
      event.target.closest('.sale-line').remove();
    }
    invalidateQuote();
  });
  saleForm?.elements.paymentMethod.addEventListener('change', () => {
    const method = saleForm.elements.paymentMethod.value;
    saleForm.elements.paymentReference.required = method !== 'CASH';
    document.getElementById('payment-reference-label').hidden = method === 'CASH';
    document.querySelectorAll('[data-payment]').forEach(button => {
      button.classList.toggle('selected', button.dataset.payment === method);
      button.setAttribute('aria-pressed', String(button.dataset.payment === method));
    });
    saleForm.elements.paymentConfirmed.checked = false;
  });
  document.querySelectorAll('[data-payment]').forEach(button => {
    button.addEventListener('click', () => {
      saleForm.elements.paymentMethod.value = button.dataset.payment;
      saleForm.elements.paymentMethod.dispatchEvent(new Event('change'));
    });
  });
  const quoteSale = async () => {
    const button = document.getElementById('quote-sale');
    if (button.disabled) return;
    clearTimeout(quoteTimer);
    for (const field of [...saleLines.querySelectorAll('input, select'), saleForm.elements.saleDate]) {
      if (!field.checkValidity()) {
        if (field.closest('details')) field.closest('details').open = true;
        field.reportValidity();
        return;
      }
    }
    const input = readSaleQuote();
    const fingerprint = JSON.stringify(input);
    const output = document.getElementById('sale-total');
    quotedLines = null;
    saleForm.elements.paymentConfirmed.checked = false;
    saleLines.querySelectorAll('.line-total').forEach(total => { total.textContent = '--'; });
    saleCount();
    button.disabled = true;
    try {
      const quote = await request('api/sales/quote', {
        method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(input)
      });
      if (JSON.stringify(readSaleQuote()) !== fingerprint) throw new Error('Lines or sale date changed. Calculate the total again.');
      quotedLines = fingerprint;
      output.className = '';
      output.textContent = `Total: ${quote.currency} ${quote.total}`;
      [...saleLines.children].forEach((line, index) => {
        line.querySelector('.line-total').textContent = `${quote.currency} ${quote.lines[index].total}`;
      });
    } catch (error) {
      if (JSON.stringify(readSaleQuote()) !== fingerprint) return;
      quotedLines = null;
      output.className = 'error';
      output.textContent = error instanceof TypeError ? 'Network error while calculating the total. Retry.' : error.message;
    } finally {
      button.disabled = false;
      if (JSON.stringify(readSaleQuote()) !== fingerprint) invalidateQuote();
    }
  };
  document.getElementById('quote-sale')?.addEventListener('click', quoteSale);
  document.getElementById('print-receipt')?.addEventListener('click', () => window.print());
  document.getElementById('print-report')?.addEventListener('click', () => window.print());
  const saleDateForm = document.getElementById('sale-date-form');
  if (saleDateForm) {
    if (window.location.hash === '#date-correction') saleDateForm.closest('details').open = true;
    let identity;
    let requestId;
    saleDateForm.addEventListener('submit', async event => {
      event.preventDefault();
      const data = {saleDate: saleDateForm.elements.saleDate.value,
        expectedVersion: Number(saleDateForm.elements.expectedVersion.value),
        reason: saleDateForm.elements.reason.value.trim()};
      const submit = saleDateForm.querySelector('[type="submit"]');
      const message = saleDateForm.querySelector('.form-message');
      try {
        const nextIdentity = JSON.stringify(data);
        if (identity !== nextIdentity) {
          requestId = newRequestId();
          identity = nextIdentity;
        }
        submit.disabled = true;
        message.className = 'form-message';
        message.textContent = 'Saving...';
        const receipt = await request(`api/sales/${encodeURIComponent(saleDateForm.dataset.saleId)}/date`, {
          method: 'POST', headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({...data, requestId})
        });
        window.location.assign(base + `ui/sales/${encodeURIComponent(receipt.id)}`);
      } catch (error) {
        message.className = 'form-message error';
        message.textContent = error instanceof TypeError
          ? 'Network error. Retry unchanged to check whether the correction saved.' : error.message;
      } finally {
        submit.disabled = false;
      }
    });
  }
  [saleForm, document.getElementById('sale-void-form')].filter(Boolean).forEach(form => {
    form.addEventListener('submit', async event => {
      event.preventDefault();
      const data = {};
      let path = 'api/sales';
      if (form === saleForm) {
        data.customer = customer.value === '__new__' ? form.elements.customerName.value.trim() : customer.value;
        data.paymentMethod = form.elements.paymentMethod.value;
        data.paymentReference = form.elements.paymentReference.value || null;
        data.notes = form.elements.notes.value || null;
        data.lines = readSaleLines();
        data.saleDate = form.elements.saleDate.value;
        if (quotedLines !== JSON.stringify({lines: data.lines, saleDate: data.saleDate})) {
          const message = form.querySelector('.form-message');
          message.className = 'form-message error';
          message.textContent = 'Calculate the current total before confirming payment and posting.';
          return;
        }
      } else {
        path += `/${encodeURIComponent(form.dataset.saleId)}/void`;
        data.reason = form.elements.reason.value;
        data.refundReference = form.elements.refundReference.value || null;
        data.returnedAndRefunded = form.elements.returnedAndRefunded.checked;
      }
      const submit = form.querySelector('[type="submit"]');
      const message = form.querySelector('.form-message');
      submit.disabled = true;
      message.textContent = 'Posting...';
      message.className = 'form-message';
      try {
        form.dataset.requestId ||= newRequestId();
        data.requestId = form.dataset.requestId;
        const result = await request(path, {
          method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(data)
        });
        window.location.assign(base + `ui/sales/${encodeURIComponent(result.id)}`);
      } catch (error) {
        message.className = 'form-message error';
        message.textContent = error instanceof TypeError
          ? 'Network error. Retry unchanged without refreshing or clearing this form to avoid duplicate posting.' : error.message;
      } finally {
        submit.disabled = false;
      }
    });
  });
  const updateReceiptGrades = line => {
    const type = line.querySelector('[name="productId"]').selectedOptions[0]?.dataset.type;
    const select = line.querySelector('[name="gradeId"]');
    [...select.options].forEach(option => {
      const allowed = !option.value || (type && option.dataset.type === type);
      option.disabled = !allowed;
      option.hidden = !allowed;
    });
    if (select.selectedOptions[0]?.disabled) select.value = '';
  };
  receiptLines?.querySelectorAll('.receipt-line').forEach(updateReceiptGrades);
  receiptLines?.addEventListener('change', event => {
    if (event.target.name === 'productId') updateReceiptGrades(event.target.closest('.receipt-line'));
  });
  const addReceiptLine = () => {
    if (receiptLines.children.length >= 50) {
      showNotice('A receipt can contain at most 50 product lines.', true);
      return;
    }
    const line = receiptLines.firstElementChild.cloneNode(true);
    line.querySelectorAll('input, select').forEach(field => { field.value = ''; });
    line.querySelectorAll('details').forEach(details => { details.open = false; });
    updateReceiptGrades(line);
    receiptLines.append(line);
    return line;
  };
  document.getElementById('add-receipt-line')?.addEventListener('click', () => {
    addReceiptLine()?.querySelector('[name="productId"]').focus();
  });
  receiptLines?.addEventListener('click', event => {
    if (!event.target.closest('[data-remove-line]')) return;
    if (receiptLines.children.length === 1) {
      showNotice('A receipt needs at least one product line.', true);
      return;
    }
    event.target.closest('.receipt-line').remove();
  });
  const picker = document.querySelector('[data-entry-mode]');
  if (picker) {
    const cards = [...picker.querySelectorAll('[data-add-product]')];
    const search = document.getElementById('product-search');
    const filters = picker.querySelector('.product-filters');
    let category = '';
    const filterProducts = () => {
      const text = search.value.trim().toLocaleLowerCase();
      cards.forEach(card => {
        card.hidden = (category && card.dataset.type !== category)
          || !`${card.dataset.name} ${card.dataset.category}`.toLocaleLowerCase().includes(text);
      });
      document.getElementById('product-empty').hidden = cards.some(card => !card.hidden);
    };
    const categories = new Map(cards.map(card => [card.dataset.type, card.dataset.category]));
    categories.forEach((name, id) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = name;
      button.dataset.productFilter = id;
      button.setAttribute('aria-pressed', 'false');
      filters.append(button);
    });
    filters.addEventListener('click', event => {
      const selected = event.target.closest('[data-product-filter]');
      if (!selected) return;
      category = selected.dataset.productFilter;
      filters.querySelectorAll('button').forEach(button => {
        button.classList.toggle('selected', button === selected);
        button.setAttribute('aria-pressed', String(button === selected));
      });
      filterProducts();
    });
    search.addEventListener('input', filterProducts);
    cards.forEach(card => {
      const name = `${card.dataset.name} ${card.dataset.category}`.toLowerCase().replace(/\s+/g, '');
      const artwork = ['onion', 'tomato', 'potato', 'cabbage', 'carrot', 'watermelon', 'capsicum', 'butternut']
        .find(type => name.includes(type));
      if (artwork) {
        const use = card.querySelector('.product-art use');
        use.setAttribute('href', `${use.getAttribute('href').split('#')[0]}#produce-${artwork}`);
      }
      card.addEventListener('click', () => {
        const selling = picker.dataset.entryMode === 'sale';
        const container = selling ? saleLines : receiptLines;
        const empty = [...container.children].find(row => !row.querySelector('[name="productId"]').value);
        if (selling && !empty) {
          const matching = [...container.children].find(line =>
            line.querySelector('[name="productId"]').value === card.dataset.id
            && line.querySelector('[name="unitId"]').value === card.dataset.unit
            && !line.querySelector('[name="measuredBaseQuantity"]').value);
          if (matching) {
            changeQuantity(matching, '1');
            return;
          }
        }
        const line = empty || (selling ? addSaleLine() : addReceiptLine());
        if (!line) return;
        line.querySelector('[name="productId"]').value = card.dataset.id;
        line.querySelector('[name="unitId"]').value = card.dataset.unit;
        if (selling) {
          if (!line.querySelector('[name="quantity"]').value) line.querySelector('[name="quantity"]').value = '1';
          invalidateQuote();
          line.querySelector('[name="unitPrice"]').focus({preventScroll: window.matchMedia('(min-width: 1151px)').matches});
        } else {
          updateReceiptGrades(line);
          const source = document.getElementById('stock-receipt').elements.source.value;
          if (source.length <= 64 && !line.querySelector('[name="lotCode"]').value) line.querySelector('[name="lotCode"]').value = source;
          line.querySelector('[name="quantity"]').focus({preventScroll: window.matchMedia('(min-width: 1151px)').matches});
        }
      });
    });
  }
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
      const submit = form.querySelector('[type="submit"]');
      submit.disabled = true;
      message.className = 'form-message';
      message.textContent = 'Posting...';
      try {
        if (resource !== 'thresholds') {
          form.dataset.requestId ||= newRequestId();
          data.requestId = form.dataset.requestId;
        }
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
        setTimeout(() => receiptLines.querySelectorAll('.receipt-line').forEach(updateReceiptGrades), 0);
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
      if (resource === 'branches' && !id && form.dataset.createAllowed !== 'true') {
        message.className = 'form-message error';
        message.textContent = 'Creating a branch requires business-wide staff access. Choose an assigned branch to edit.';
        return;
      }
      const method = resource === 'packaging' || id ? 'PUT' : 'POST';
      submit.disabled = true;
      message.className = 'form-message';
      message.textContent = 'Working...';
      try {
        const endpoint = resource === 'branches' ? 'api/branches' : `api/catalogue/${resource}`;
        const result = await request(endpoint + (id ? '/' + encodeURIComponent(id) : ''), {
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
        message.textContent = error instanceof TypeError
          ? 'Network error. Reload the list to check whether the change saved before retrying.' : error.message;
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
      if (resource === 'branches') {
        form.querySelector('[type="submit"]').textContent = 'Add branch';
        document.getElementById('branches-editor').hidden = form.dataset.createAllowed !== 'true';
      }
    });
  });

  document.querySelectorAll('[data-edit]').forEach(button => {
    button.addEventListener('click', async () => {
      button.disabled = true;
      try {
        const resource = button.dataset.edit;
        const snapshot = await request(resource === 'branches'
          ? 'api/branches/' + encodeURIComponent(button.dataset.id) : 'api/catalogue');
        const item = resource === 'branches' ? snapshot : snapshot[resource].find(record => record.id === button.dataset.id);
        if (!item) throw new Error('Record no longer exists. Reload the list.');
        const form = document.querySelector(`form[data-resource="${resource}"]`);
        form.reset();
        for (const [name, value] of Object.entries(item)) {
          const field = form.elements.namedItem(name);
          if (!field) continue;
          if (field.type === 'checkbox') field.checked = value;
          else field.value = value ?? '';
        }
        const editor = document.getElementById(`${resource}-editor`);
        if (resource === 'branches') {
          editor.hidden = false;
          form.querySelector('[type="submit"]').textContent = 'Save branch';
        }
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
