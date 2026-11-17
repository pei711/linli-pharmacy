(() => {
  const API = '';
  const TOKEN_KEY = 'neighbor-pharmacy-token';
  const $ = (id) => document.getElementById(id);
  const dialog = $('login-dialog');
  const ordersDialog = $('orders-dialog');
  const state = { stores: [], selectedStore: null, token: sessionStorage.getItem(TOKEN_KEY), pendingActivity: null };

  function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
  }

  async function request(path, options = {}) {
    const headers = { ...(options.headers || {}) };
    if (state.token) headers.authorization = state.token;
    if (options.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
    const response = await fetch(`${API}${path}`, { ...options, headers });
    let result;
    try { result = await response.json(); } catch { throw new Error(`服务器返回异常 (${response.status})`); }
    if (!response.ok || result.success === false) throw new Error(result.errorMsg || `请求失败 (${response.status})`);
    return result.data;
  }

  function toast(message) {
    const node = $('toast');
    node.textContent = message;
    node.classList.add('show');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => node.classList.remove('show'), 2800);
  }

  function showLogin(message = '') {
    $('login-message').textContent = message;
    if (!dialog.open) dialog.showModal();
  }

  function newRequestId() {
    if (window.crypto?.randomUUID) return window.crypto.randomUUID();
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (digit) => {
      const random = Math.floor(Math.random() * 16);
      return (digit === 'x' ? random : (random & 3) | 8).toString(16);
    });
  }

  function renderStores(stores) {
    state.stores = stores || [];
    $('store-count').textContent = `${state.stores.length} 家`;
    if (!state.stores.length) {
      $('store-list').innerHTML = '<div class="empty-note">这个范围内还没有登记坐标的药房。扩大搜索范围，或稍后再来看看。</div>';
      return;
    }
    $('store-list').innerHTML = state.stores.map((store, index) => `
      <button class="store-card ${state.selectedStore?.id === store.id ? 'selected' : ''}" data-store-id="${Number(store.id)}" type="button">
        <div class="store-top"><span class="store-name">${escapeHtml(store.name || '社区药房')}</span><span class="store-distance">${Number(store.distanceKm || 0).toFixed(1)} km</span></div>
        <div class="store-address">${escapeHtml(store.address || '门店地址待完善')}</div>
        <div class="store-meta"><span class="mini-dot"></span>批次信息可查</div>
      </button>`).join('');
    $('store-list').querySelectorAll('[data-store-id]').forEach((button) => button.addEventListener('click', () => {
      const store = state.stores.find((item) => Number(item.id) === Number(button.dataset.storeId));
      if (store) selectStore(store);
    }));
  }

  async function findNearby(event) {
    if (event) event.preventDefault();
    const longitude = Number($('longitude').value);
    const latitude = Number($('latitude').value);
    const radius = Number($('radius').value);
    $('store-list').innerHTML = '<div class="skeleton-card"></div><div class="skeleton-card"></div>';
    try {
      const query = new URLSearchParams({ longitude, latitude, radiusKm: radius, limit: 20 });
      const stores = await request(`/api/v1/stores/nearby?${query}`);
      renderStores(stores);
      if (stores.length) await selectStore(stores[0]);
      else {
        state.selectedStore = null;
        $('selected-store').innerHTML = '<span class="empty-icon">⌖</span><div><h3>暂未找到附近药房</h3><p>试试扩大搜索范围，或更换经纬度。</p></div>';
        $('medicine-list').innerHTML = '';
        $('activity-list').innerHTML = '<div class="activity-empty">附近门店的预约活动会显示在这里。</div>';
        $('activity-count').textContent = '暂无活动';
      }
    } catch (error) {
      $('store-list').innerHTML = `<div class="empty-note">暂时无法读取门店：${escapeHtml(error.message)}</div>`;
    }
  }

  async function selectStore(store) {
    state.selectedStore = store;
    renderStores(state.stores);
    $('selected-store').classList.remove('empty-state');
    $('selected-store').innerHTML = `<div class="empty-icon">⌖</div><div><h3>${escapeHtml(store.name || '社区药房')}</h3><div class="selected-address"><span>${escapeHtml(store.address || '门店地址待完善')}</span><span>${Number(store.distanceKm || 0).toFixed(1)} 公里</span></div></div>`;
    $('medicine-list').innerHTML = '<div class="skeleton-card"></div><div class="skeleton-card"></div>';
    $('activity-list').innerHTML = '<div class="activity-empty">正在读取活动…</div>';
    try {
      const [medicines, activities] = await Promise.all([
        request(`/api/v1/stores/${Number(store.id)}/medicines`),
        request(`/api/v1/stores/${Number(store.id)}/activities`)
      ]);
      renderMedicines(medicines);
      renderActivities(activities);
    } catch (error) {
      $('medicine-list').innerHTML = `<div class="empty-note">商品读取失败：${escapeHtml(error.message)}</div>`;
      $('activity-list').innerHTML = '<div class="activity-empty">暂时无法读取预约活动。</div>';
    }
  }

  function renderMedicines(medicines) {
    if (!medicines?.length) {
      $('medicine-list').innerHTML = '<div class="empty-note">这家门店还没有录入可预约 OTC 商品或医疗用品。</div>';
      return;
    }
    $('medicine-list').innerHTML = medicines.map((medicine) => {
      const supply = medicine.sale_category === 'MEDICAL_SUPPLY';
      return `<article class="medicine-card"><div><span class="tag ${supply ? 'supply' : ''}">${supply ? '医疗用品' : 'OTC 商品'}</span><h4>${escapeHtml(medicine.name)}</h4><div class="medicine-spec">${escapeHtml(medicine.specification)}</div></div><div class="medicine-bottom"><span class="medicine-spec">可用批次库存</span><span class="stock-count"><strong>${Number(medicine.available_qty || 0)}</strong> 件</span></div></article>`;
    }).join('');
  }

  function renderActivities(activities) {
    $('activity-count').textContent = `${activities?.length || 0} 场`;
    if (!activities?.length) {
      $('activity-list').innerHTML = '<div class="activity-empty">当前没有正在进行或即将开始的限量预约。</div>';
      return;
    }
    const now = Date.now();
    $('activity-list').innerHTML = activities.map((activity) => {
      const start = new Date(String(activity.start_at).replace(' ', 'T')).getTime();
      const remaining = Number(activity.remaining_qty || 0);
      const waiting = start > now;
      const soldOut = remaining <= 0;
      const label = soldOut ? '已约满' : (waiting ? '即将开始' : '立即预约');
      return `<article class="activity-card"><div class="activity-copy"><div class="activity-kicker">LIMITED RESERVATION · ${escapeHtml(activity.name || '社区药房')}</div><h3>${escapeHtml(activity.title)}</h3><div class="activity-details">${escapeHtml(activity.specification)}　·　每人限约 <b>${Number(activity.per_user_limit)}</b> 件<br>开始 ${escapeHtml(activity.start_at)}　至　${escapeHtml(activity.end_at)}</div></div><div class="activity-right"><div class="activity-stock">余量 <strong>${remaining}</strong> 件</div><button class="button reserve-button" data-activity-id="${Number(activity.id)}" type="button" ${waiting || soldOut ? 'disabled' : ''}>${label}</button></div></article>`;
    }).join('');
    $('activity-list').querySelectorAll('[data-activity-id]').forEach((button) => button.addEventListener('click', () => reserve(Number(button.dataset.activityId), button)));
  }

  async function reserve(activityId, button) {
    if (!state.token) {
      state.pendingActivity = activityId;
      showLogin('登录后即可预约，成功请求会自动继续。');
      return;
    }
    button.disabled = true;
    button.textContent = '提交中…';
    const requestId = newRequestId();
    try {
      const accepted = await request(`/api/v1/activities/${activityId}/reserve`, {
        method: 'POST', headers: { 'Idempotency-Key': requestId }, body: JSON.stringify({ quantity: 1 })
      });
      toast('预约请求已受理，正在确认订单…');
      await pollRequest(requestId, accepted);
    } catch (error) {
      toast(error.message);
      button.disabled = false;
      button.textContent = '再试一次';
    }
  }

  async function pollRequest(requestId, firstResult) {
    let result = firstResult;
    for (let attempt = 0; attempt < 30; attempt += 1) {
      if (!result?.status || result.status === 'PROCESSING' || result.status === 'INITIALIZING') {
        result = await request(`/api/v1/seckill/requests/${encodeURIComponent(requestId)}`);
      }
      if (result.status === 'SUCCESS') {
        toast(`预约成功，订单号 ${result.orderNo}`);
        await selectStore(state.selectedStore);
        return;
      }
      if (['FAILED', 'EXPIRED', 'CANCELLED'].includes(result.status)) {
        toast(result.failureCode ? `预约未完成：${result.failureCode}` : '预约未完成，请查看活动余量后重试');
        await selectStore(state.selectedStore);
        return;
      }
      await new Promise((resolve) => setTimeout(resolve, 1200));
    }
    toast('订单仍在处理中，可稍后重新查看。');
  }

  async function sendCode() {
    const phone = $('login-phone').value.trim();
    if (!/^1\d{10}$/.test(phone)) {
      $('login-message').textContent = '请填写有效的 11 位手机号。';
      return;
    }
    try {
      const data = await request(`/user/code?phone=${encodeURIComponent(phone)}`, { method: 'POST' });
      $('login-message').textContent = data?.code ? `演示验证码：${data.code}` : '验证码已发送，请查收短信。';
    } catch (error) {
      $('login-message').textContent = error.message;
    }
  }

  async function login(event) {
    event.preventDefault();
    const phone = $('login-phone').value.trim();
    const code = $('login-code').value.trim();
    try {
      const token = await request('/user/login', { method: 'POST', body: JSON.stringify({ phone, code }) });
      if (!token) throw new Error('登录未返回有效凭证');
      state.token = token;
      sessionStorage.setItem(TOKEN_KEY, token);
      updateAccountLabel(phone);
      dialog.close();
      toast('登录成功');
      if (state.pendingActivity) {
        const activityId = state.pendingActivity;
        state.pendingActivity = null;
        const button = document.querySelector(`[data-activity-id="${activityId}"]`);
        if (button) await reserve(activityId, button);
      }
    } catch (error) {
      $('login-message').textContent = error.message;
    }
  }

  function updateAccountLabel(phone = '') {
    $('account-label').textContent = state.token ? `${phone || '已登录'} · 我的预约` : '登录 / 我的预约';
    $('account-button').classList.toggle('signed-in', Boolean(state.token));
  }

  async function showOrders() {
    $('orders-list').innerHTML = '<div class="empty-note">正在读取订单…</div>';
    ordersDialog.showModal();
    try {
      const orders = await request('/api/v1/orders/mine');
      if (!orders?.length) {
        $('orders-list').innerHTML = '<div class="empty-note">还没有预约订单。可以先看看附近的限量活动。</div>';
        return;
      }
      const labels = { RESERVED: '待到店核销', COMPLETED: '已完成', CANCELLED: '已取消', EXPIRED: '已过期' };
      $('orders-list').innerHTML = orders.map((order) => `<article class="order-card"><div class="order-head"><span>${escapeHtml(order.medicine_name)} × ${Number(order.quantity)}</span><span class="order-status">${labels[order.status] || escapeHtml(order.status)}</span></div><div class="order-meta">${escapeHtml(order.store_name)}<br>订单号 ${escapeHtml(order.order_no)}<br>请于 ${escapeHtml(order.pickup_expires_at)} 前到店</div>${order.status === 'RESERVED' ? `<button class="cancel-order" data-order-no="${escapeHtml(order.order_no)}" type="button">取消预约</button>` : ''}</article>`).join('');
      $('orders-list').querySelectorAll('[data-order-no]').forEach((button) => button.addEventListener('click', async () => {
        if (!confirm('确定取消这笔预约吗？可售批次会按库存规则释放。')) return;
        button.disabled = true;
        try {
          await request(`/api/v1/orders/${encodeURIComponent(button.dataset.orderNo)}/cancel`, { method: 'POST' });
          toast('预约已取消');
          await showOrders();
          await selectStore(state.selectedStore);
        } catch (error) {
          toast(error.message);
          button.disabled = false;
        }
      }));
    } catch (error) {
      $('orders-list').innerHTML = `<div class="empty-note">订单读取失败：${escapeHtml(error.message)}</div>`;
    }
  }

  $('location-form').addEventListener('submit', findNearby);
  $('send-code').addEventListener('click', sendCode);
  $('login-form').addEventListener('submit', login);
  $('account-button').addEventListener('click', () => {
    if (state.token) showOrders();
    else showLogin();
  });
  $('logout-button').addEventListener('click', () => {
    state.token = null;
    sessionStorage.removeItem(TOKEN_KEY);
    updateAccountLabel();
    ordersDialog.close();
    toast('已退出登录');
  });
  $('use-location').addEventListener('click', () => {
    if (!navigator.geolocation) return toast('此浏览器暂不支持定位');
    navigator.geolocation.getCurrentPosition((position) => {
      $('longitude').value = position.coords.longitude.toFixed(6);
      $('latitude').value = position.coords.latitude.toFixed(6);
      findNearby();
    }, () => toast('无法获取定位，请手动输入经纬度。'), { enableHighAccuracy: true, timeout: 7000 });
  });

  updateAccountLabel();
  findNearby();
})();
