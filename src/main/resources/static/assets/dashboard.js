(() => {
  const namespace = 'http://www.w3.org/2000/svg';
  const colors = { sales: '#347b70', previous: '#a8cbbd', cost: '#cfb376', loss: '#ac3434' };
  const element = (name, attributes = {}, text) => {
    const node = document.createElementNS(namespace, name);
    Object.entries(attributes).forEach(([key, value]) => node.setAttribute(key, String(value)));
    if (text !== undefined) node.textContent = text;
    return node;
  };
  const svg = (panel, width, height, label) => {
    const node = element('svg', { viewBox: `0 0 ${width} ${height}`, role: 'img', 'aria-label': label });
    node.append(element('title', {}, label));
    panel.querySelector('.chart-visual').append(node);
    return node;
  };
  const money = (value, currency) => new Intl.NumberFormat(undefined, {
    style: 'currency', currency
  }).format(value);
  const text = (node, x, y, value, attributes = {}) =>
    node.append(element('text', { x, y, fill: '#52685b', 'font-size': 13, ...attributes }, value));
  document.querySelectorAll('.currency-insights').forEach(section => {
    const currency = section.dataset.currency;
    section.querySelectorAll('[data-chart]').forEach(panel => {
      const rows = [...panel.querySelectorAll('tbody tr')];
      if (panel.dataset.chart === 'donut') {
        if (panel.dataset.available !== 'true') return;
        const sales = Number(panel.dataset.revenue);
        const costShare = Number(panel.dataset.cost) / sales;
        panel.querySelectorAll('.breakdown-legend dd').forEach((amount, index) => {
          const share = document.createElement('span');
          share.className = 'share-label';
          share.textContent = `${((index === 0 ? costShare : 1 - costShare) * 100).toFixed(1)}%`;
          amount.append(share);
        });
        const node = svg(panel, 260, 220, `${currency} net sales split between COGS and gross profit`);
        node.append(element('circle', { cx: 130, cy: 110, r: 82, fill: 'none', stroke: colors.sales, 'stroke-width': 30 }));
        const circumference = 2 * Math.PI * 82;
        const cost = element('circle', { cx: 130, cy: 110, r: 82, fill: 'none', stroke: colors.cost,
          'stroke-width': 30, 'stroke-dasharray': `${costShare * circumference} ${circumference}`,
          transform: 'rotate(-90 130 110)' });
        cost.append(element('title', {}, `COGS: ${money(Number(panel.dataset.cost), currency)}`));
        node.append(cost);
        text(node, 130, 105, 'Net sales', { 'text-anchor': 'middle', 'font-size': 14 });
        text(node, 130, 131, new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(sales),
          { 'text-anchor': 'middle', 'font-size': 24, 'font-weight': 650, fill: '#20313e' });
        cost.append(element('title', {}, `COGS ${(costShare * 100).toFixed(1)}%; gross profit ${((1 - costShare) * 100).toFixed(1)}%`));
      } else if (panel.dataset.chart === 'line') {
        if (!rows.length) return;
        const values = rows.flatMap(row => ['value', 'previous'].flatMap(key =>
          row.dataset[key] === undefined ? [] : [Number(row.dataset[key])]));
        const low = Math.min(0, ...values);
        const high = Math.max(1, ...values);
        const y = value => 230 - (value - low) / (high - low) * 195;
        const x = index => 50 + index / Math.max(1, rows.length - 1) * 350;
        const node = svg(panel, 420, 270, `${currency} daily net sales compared with previous period`);
        for (let tick = 0; tick <= 4; tick++) {
          const value = low + (high - low) * tick / 4;
          node.append(element('line', { x1: 50, x2: 400, y1: y(value), y2: y(value), stroke: '#edf1f4', 'stroke-dasharray': '4 4' }));
          text(node, 42, y(value) + 4, new Intl.NumberFormat(undefined, { notation: 'compact' }).format(value),
            { 'text-anchor': 'end', 'font-size': 12 });
        }
        ['value', 'previous'].forEach((key, series) => {
          const indexed = rows.flatMap((row, index) =>
            row.dataset[key] === undefined ? [] : [{ x: x(index), y: y(Number(row.dataset[key])) }]);
          const points = indexed.map(point => `${point.x},${point.y}`).join(' ');
          if (!series && indexed.length) {
            node.append(element('polygon', { points: `${indexed[0].x},${y(0)} ${points} ${indexed.at(-1).x},${y(0)}`,
              fill: colors.sales, 'fill-opacity': .07 }));
          }
          node.append(element('polyline', { points, fill: 'none', stroke: series ? colors.previous : colors.sales,
            'stroke-width': 2, 'stroke-dasharray': series ? '6 4' : 'none' }));
          rows.forEach((row, index) => {
            if (row.dataset[key] === undefined) return;
            const point = element('circle', { cx: x(index), cy: y(Number(row.dataset[key])), r: 3,
              fill: series ? colors.previous : colors.sales });
            point.append(element('title', {}, `Day ${row.dataset.day}, ${series ? 'previous' : 'selected'}: ${money(Number(row.dataset[key]), currency)}`));
            node.append(point);
          });
        });
        rows.forEach((row, index) => {
          if (index === 0 || index === rows.length - 1 || (index + 1) % 5 === 0)
            text(node, x(index), 254, row.dataset.day, { 'text-anchor': 'middle', 'font-size': 11 });
        });
      } else if (panel.dataset.chart === 'bar') {
        if (!rows.length) return;
        const sellers = rows.map(row => ({ label: row.dataset.label, value: Number(row.dataset.value) }))
          .sort((a, b) => b.value - a.value);
        const shown = sellers.slice(0, 10);
        if (sellers.length > 10) shown.push({ label: `Other (${sellers.length - 10})`,
          value: sellers.slice(10).reduce((sum, seller) => sum + seller.value, 0) });
        const low = Math.min(0, ...shown.map(seller => seller.value));
        const high = Math.max(1, ...shown.map(seller => seller.value));
        const x = value => 95 + (value - low) / (high - low) * 160;
        const node = svg(panel, 360, 25 + shown.length * 44, `${currency} net sales by salesperson, top ten and Other`);
        node.append(element('line', { x1: x(0), x2: x(0), y1: 8, y2: shown.length * 44 + 10, stroke: '#dfe6ed' }));
        shown.forEach((seller, index) => {
          const y = 15 + index * 44;
          const name = seller.label.split(' / ')[0];
          const label = name.length > 13 ? name.slice(0, 10) + '...' : name;
          text(node, 0, y + 16, label, { 'font-size': 12 });
          node.append(element('rect', { x: 95, y, width: 160, height: 20, rx: 10, fill: '#f5f7f9' }));
          const bar = element('rect', { x: Math.min(x(0), x(seller.value)), y, width: Math.abs(x(seller.value) - x(0)),
            height: 20, rx: 10, fill: seller.value < 0 ? colors.loss : colors.sales });
          bar.append(element('title', {}, `${seller.label}: ${money(seller.value, currency)}`));
          node.append(bar);
          text(node, 356, y + 16, money(seller.value, currency), { 'text-anchor': 'end', 'font-size': 10 });
        });
      }
    });
  });
  const month = document.getElementById('dashboard-month');
  if (month) month.addEventListener('change', () => {
    if (month.checkValidity()) month.form.requestSubmit();
  });
  const currency = document.getElementById('dashboard-currency');
  if (currency) currency.addEventListener('change', () => {
    if (currency.form.checkValidity()) currency.form.requestSubmit();
  });
})();
