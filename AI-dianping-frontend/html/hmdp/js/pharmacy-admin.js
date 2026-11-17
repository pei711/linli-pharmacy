(() => {
  const $ = (selector) => document.querySelector(selector);
  const dialog = $('#login-dialog');
  const status = $('#auth-state');
  const savedStore = sessionStorage.getItem('pharmacyStoreId');
  let pendingInboundDocumentId = null;
  if (savedStore) $('#store-id').value = savedStore;

  const setStatus = (text, online = false) => {
    status.textContent = text;
    status.classList.toggle('on', online);
  };
  const message = (target, text, error = false) => {
    target.textContent = text;
    target.classList.toggle('error', error);
  };
  const unwrap = (response) => response;
  const displayError = (error) => typeof error === 'string' ? error : (error?.response?.data?.errorMsg || '请求失败，请检查网络和登录状态。');

  const currentToken = sessionStorage.getItem('token');
  if (currentToken) {
    axios.get('/user/me').then((response) => {
      const user = unwrap(response).data;
      setStatus(user?.nickName || '已登录', true);
      $('#login-open').innerHTML = '退出登录 <span>↗</span>';
    }).catch(() => setStatus('登录已失效'));
  }

  $('#login-open').addEventListener('click', async () => {
    if (!sessionStorage.getItem('token')) return dialog.showModal();
    try { await axios.post('/user/logout'); } catch (_) { /* local session still clears */ }
    sessionStorage.removeItem('token');
    location.reload();
  });
  $('#token-cancel').addEventListener('click', () => dialog.close());
  $('#token-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const phone = $('#phone-input').value.trim();
    const code = $('#token-input').value.trim();
    try {
      const result = unwrap(await axios.post('/user/login', { phone, code }));
      if (!result?.success || !result.data) throw new Error(result?.errorMsg || '验证码错误');
      sessionStorage.setItem('token', result.data);
      dialog.close();
      location.reload();
    } catch (error) {
      message($('#token-form').querySelector('p'), displayError(error), true);
    }
  });
  $('#send-code').addEventListener('click', async () => {
    const phone = $('#phone-input').value.trim();
    if (!/^1\d{10}$/.test(phone)) {
      message($('#login-message'), '请先输入有效的 11 位手机号。', true);
      return;
    }
    try {
      const result = unwrap(await axios.post('/user/code', null, { params: { phone } }));
      const testCode = result?.data?.code;
      message($('#login-message'), testCode ? `测试验证码：${testCode}（仅限当前隔离测试环境）` : '验证码已发送，请查收短信。');
    } catch (error) {
      message($('#login-message'), displayError(error), true);
    }
  });

  $('#ocr-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    const storeId = Number(form.elements.storeId.value);
    const file = form.elements.file.files[0];
    const button = $('#ocr-submit');
    const output = $('#ocr-message');
    if (!Number.isSafeInteger(storeId) || storeId <= 0 || !file) return message(output, '请填写门店编号并选择单据图片。', true);
    const payload = new FormData();
    payload.append('file', file);
    button.disabled = true;
    $('#ocr-review').hidden = true;
    message(output, '正在识别单据并生成待复核草稿…');
    try {
      const response = unwrap(await axios.post(`/v1/admin/inbound-documents/recognize?storeId=${encodeURIComponent(storeId)}`, payload));
      const data = response.data;
      pendingInboundDocumentId = data.documentId;
      const confirmForm = $('#ocr-confirm-form');
      const candidates = data.candidates?.length ? data.candidates : [data.candidate || {}];
      const summary = $('#ocr-candidate-summary');
      summary.replaceChildren();
      const items = $('#ocr-items');
      items.replaceChildren();
      candidates.forEach((candidate, index) => {
        const card = document.createElement('section');
        card.className = 'ocr-item-review';
        const heading = document.createElement('p');
        heading.textContent = `商品 ${index + 1}：${candidate.medicine_name || '未识别'}；规格：${candidate.specification || '未识别'}；批号：${candidate.batch_no || '未识别'}；生产日期：${candidate.production_date || '未识别'}；有效期：${candidate.expire_date || '未识别'}；数量：${candidate.quantity || '未识别'}；采购单价：${candidate.purchase_unit_price ?? '未识别'}`;
        card.append(heading);
        const fields = document.createElement('div');
        fields.className = 'ocr-item-fields';
        const field = (labelText, name, type, value, required = true) => {
          const label = document.createElement('label');
          label.textContent = labelText;
          const input = document.createElement('input');
          input.name = name;
          input.type = type;
          input.value = value == null ? '' : value;
          input.required = required;
          if (type === 'number') input.min = '1';
          if (name === 'batchNo') input.maxLength = 100;
          label.append(input);
          fields.append(label);
          return input;
        };
        field('匹配药品编号', 'medicineId', 'number', '');
        field('规格', 'specification', 'text', candidate.specification);
        field('批次号', 'batchNo', 'text', candidate.batch_no);
        field('生产日期', 'productionDate', 'date', candidate.production_date);
        field('有效期至', 'expireDate', 'date', candidate.expire_date);
        field('入库数量', 'quantity', 'number', candidate.quantity);
        const price = field('采购单价（元）', 'purchaseUnitPrice', 'number', candidate.purchase_unit_price, true);
        price.min = '0';
        price.step = '0.01';
        price.max = '100000000';
        field('复核修正说明', 'correctionNote', 'text', '', false);
        const matches = document.createElement('div');
        matches.className = 'ocr-sku-matches';
        const matchLabel = document.createElement('span');
        matchLabel.textContent = '商品档案匹配建议（点击选择，仍需人工核对）：';
        matches.append(matchLabel);
        (candidate.skuCandidates || (index === 0 ? data.skuCandidates : []) || []).forEach((sku) => {
          const match = document.createElement('button');
          match.type = 'button';
          match.textContent = `${sku.name} ${sku.specification} · 编号 ${sku.medicineId} · 匹配 ${Math.round(sku.matchScore * 100)}%`;
          match.addEventListener('click', () => {
            fields.querySelector('[name="medicineId"]').value = sku.medicineId;
            fields.querySelector('[name="specification"]').value = sku.specification;
          });
          matches.append(match);
        });
        card.append(fields, matches);
        items.append(card);
      });
      const raw = document.createElement('details');
      const rawTitle = document.createElement('summary');
      rawTitle.textContent = '查看 OCR 原文';
      const rawText = document.createElement('pre');
      rawText.textContent = data.ocrText || '没有识别到文字';
      raw.append(rawTitle, rawText);
      summary.append(raw);
      confirmForm.dataset.storeId = String(storeId);
      $('#ocr-review').hidden = false;
      message(output, `识别完成，候选单据 #${data.documentId} 已保存。请核对候选字段和原文后再入库。`);
    } catch (error) {
      pendingInboundDocumentId = null;
      message(output, displayError(error) || 'OCR 服务暂不可用；库存未变更。', true);
    } finally {
      button.disabled = false;
    }
  });

  $('#ocr-confirm-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!pendingInboundDocumentId) return message($('#ocr-message'), '请先上传并识别单据。', true);
    const form = event.currentTarget;
    const payload = { items: [...form.querySelectorAll('.ocr-item-fields')].map((fields) => {
      const values = Object.fromEntries([...fields.querySelectorAll('input')].map((input) => [input.name, input.value]));
      values.medicineId = Number(values.medicineId);
      values.quantity = Number(values.quantity);
      return values;
    }) };
    const button = $('#ocr-confirm');
    button.disabled = true;
    try {
      const response = unwrap(await axios.post(`/v1/admin/inbound-documents/${pendingInboundDocumentId}/confirm-items`, payload));
      const batchIds = response.data;
      const storeId = Number(form.dataset.storeId);
      message($('#ocr-message'), `人工复核完成，${batchIds.length} 个商品批次已入库并记录 INBOUND 流水。`);
      $('#store-id').value = storeId;
      sessionStorage.setItem('pharmacyStoreId', String(storeId));
      $('#ocr-review').hidden = true;
      pendingInboundDocumentId = null;
      await loadInventory(storeId);
    } catch (error) {
      message($('#ocr-message'), displayError(error), true);
    } finally {
      button.disabled = false;
    }
  });

  $('#ocr-reject-button').addEventListener('click', async () => {
    if (!pendingInboundDocumentId) return message($('#ocr-message'), '请先上传并识别单据。', true);
    const reason = $('#ocr-reject-reason').value.trim();
    if (!reason) return message($('#ocr-message'), '请填写拒绝原因。', true);
    const button = $('#ocr-reject-button');
    button.disabled = true;
    try {
      await axios.post(`/v1/admin/inbound-documents/${pendingInboundDocumentId}/reject`, { reason });
      pendingInboundDocumentId = null;
      $('#ocr-review').hidden = true;
      message($('#ocr-message'), '草稿已拒绝；库存未变更，拒绝原因已记录。');
    } catch (error) {
      message($('#ocr-message'), displayError(error), true);
    } finally {
      button.disabled = false;
    }
  });

  $('#assistant-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const button = $('#assistant-submit');
    const answer = $('#assistant-answer');
    button.disabled = true;
    answer.textContent = '正在读取门店数据并生成摘要…';
    answer.classList.add('loading');
    try {
      const result = await axios.post('/v1/ai/assistant/chat', { message: $('#assistant-question').value.trim() });
      answer.textContent = result.data?.answer || '暂时没有可显示的分析结果。';
      answer.dataset.source = result.data?.source || 'unknown';
    } catch (error) {
      answer.textContent = typeof error === 'string' ? error : '经营助手暂时不可用，请稍后重试。';
    } finally {
      button.disabled = false;
      answer.classList.remove('loading');
    }
  });

  const makeCell = (row, content, className = '') => {
    const cell = document.createElement('td');
    if (content instanceof Node) cell.append(content);
    else cell.textContent = content == null ? '—' : String(content);
    if (className) cell.className = className;
    row.append(cell);
    return cell;
  };

  async function loadInventory(storeId) {
    const notice = $('#inventory-message');
    const wrap = $('#table-wrap');
    const body = $('#inventory-body');
    notice.hidden = false;
    wrap.hidden = true;
    notice.innerHTML = '<span class="empty-icon">◌</span><b>正在读取门店库存</b><p>请稍候…</p>';
    try {
      const result = unwrap(await axios.get('/v1/admin/inventory', { params: { storeId } }));
      const rows = result.data || [];
      body.replaceChildren();
      const medicines = new Map();
      for (const item of rows) {
        medicines.set(item.medicine_id, item.name);
        const tr = document.createElement('tr');
        const medicine = document.createElement('div');
        medicine.className = 'medicine-name';
        medicine.textContent = item.name;
        const spec = document.createElement('div');
        spec.className = 'medicine-spec';
        spec.textContent = item.specification;
        const productCell = document.createElement('td');
        productCell.append(medicine, spec);
        tr.append(productCell);
        makeCell(tr, item.batch_no);
        const expiry = makeCell(tr, item.expire_date);
        if (item.expire_date && new Date(item.expire_date) < new Date(Date.now() + 90 * 86400000)) expiry.classList.add('expire-soon');
        makeCell(tr, item.on_hand_qty);
        makeCell(tr, item.available_qty);
        makeCell(tr, item.activity_reserved_qty);
        makeCell(tr, item.order_reserved_qty);
        makeCell(tr, item.quarantined_qty);
        body.append(tr);
      }
      notice.hidden = true;
      wrap.hidden = false;
      $('#inventory-count').textContent = `${rows.length} 个批次`;
      const selector = $('#fefo-medicine');
      selector.replaceChildren(new Option('选择药品', ''));
      for (const [medicineId, name] of medicines) selector.add(new Option(name, medicineId));
      $('#refresh-inventory').hidden = false;
      if (!rows.length) {
        body.innerHTML = '<tr><td colspan="8" class="empty-row">这个门店还没有入库批次。可以在下方登记第一批到货。</td></tr>';
      }
      sessionStorage.setItem('pharmacyStoreId', String(storeId));
    } catch (error) {
      notice.innerHTML = '';
      const title = document.createElement('b');
      title.textContent = '暂时无法读取库存';
      const detail = document.createElement('p');
      detail.textContent = displayError(error);
      notice.append(title, detail);
      wrap.hidden = true;
    }
  }

  $('#store-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const storeId = Number($('#store-id').value);
    if (!Number.isSafeInteger(storeId) || storeId <= 0) return;
    sessionStorage.setItem('pharmacyStoreId', String(storeId));
    loadInventory(storeId);
  });
  $('#refresh-inventory').addEventListener('click', () => loadInventory(Number($('#store-id').value)));
  $('#fefo-load').addEventListener('click', async () => {
    const medicineId = Number($('#fefo-medicine').value);
    const target = $('#fefo-result');
    if (!medicineId) return message(target, '请先选择药品。', true);
    try {
      const result = unwrap(await axios.get('/v1/admin/inventory/fefo', { params: { storeId: Number($('#store-id').value), medicineId } }));
      const batches = result.data || [];
      target.replaceChildren();
      if (!batches.length) return message(target, '没有可售库存批次。');
      batches.forEach((batch, index) => {
        const item = document.createElement('span');
        item.className = 'fefo-chip';
        item.textContent = `${index + 1}. ${batch.batch_no} · ${batch.expire_date} · 可用 ${batch.available_qty}`;
        target.append(item);
      });
    } catch (error) {
      message(target, displayError(error), true);
    }
  });

  $('#medicine-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    const output = $('#medicine-message');
    const data = Object.fromEntries(new FormData(form));
    data.storeId = Number(data.storeId);
    message(output, '正在创建商品…');
    try {
      const result = unwrap(await axios.post('/v1/admin/medicines', data));
      const medicineId = result.data;
      message(output, `商品创建成功，商品编号：${medicineId}。登记批次时填写该编号。`);
      form.elements.storeId.value = data.storeId;
      $('#batch-form').elements.storeId.value = data.storeId;
      $('#batch-form').elements.medicineId.value = medicineId;
    } catch (error) {
      message(output, displayError(error), true);
    }
  });

  const today = new Date();
  const isoDate = (date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  $('#batch-form').elements.productionDate.value = isoDate(today);
  const nextYear = new Date(today);
  nextYear.setFullYear(nextYear.getFullYear() + 1);
  $('#batch-form').elements.expireDate.value = isoDate(nextYear);
  $('#batch-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    const output = $('#batch-message');
    const data = Object.fromEntries(new FormData(form));
    for (const key of ['storeId', 'medicineId', 'quantity']) data[key] = Number(data[key]);
    message(output, '正在登记入库…');
    try {
      const result = unwrap(await axios.post('/v1/admin/inventory/batches', data));
      message(output, `入库成功，库存批次编号：${result.data}。`);
      $('#store-id').value = data.storeId;
      sessionStorage.setItem('pharmacyStoreId', String(data.storeId));
      await loadInventory(data.storeId);
    } catch (error) {
      message(output, displayError(error), true);
    }
  });
})();
