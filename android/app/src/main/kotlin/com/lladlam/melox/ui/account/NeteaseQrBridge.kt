package com.lladlam.melox.ui.account

/**
 * 网易云扫码的「网页驱动」脚本 —— **让网页自己走完登录流程**，App 只被动接收状态。
 *
 * ## 上一版为什么失败（三处真实故障，都已实测确认）
 *
 * 1. **登录页根本没渲染二维码。** `https://music.163.com/#/login` 在移动端 UA 下返回的是
 *    旧版服务端模板，扫码面板（`#login-qrcode`）整块躺在 `<textarea>` 里的 NEJ 模板中，
 *    从未实例化。于是「自动点开扫码 tab」恒返回 `noBtn`，Hook 等到的 `seen` 恒为 `0`。
 *    ⇒ 靠解析页面自己发的请求这条路，从起点就不通。
 * 2. **App 主动读结果的两条通道都会静默失效。** 真机诊断日志里，unikey 阶段靠
 *    `document.title` 通了两拍，进入轮询后 `reportTimeout` 铺满全屏：
 *    - `document.title` **内容不变时 WebKit 不触发 `titleChanged`**，`onReceivedTitle`
 *      就不会回调 —— 状态没变化的那些拍，正是最需要读到的那些拍；
 *    - `evaluateJavascript` 在同阶段返回空串，回调拿到 `null`。
 *    ⇒ 轮询结果永远回不来，表现就是「扫码了没反应」。
 * 3. **unikey 其实能拿到。** 日志 `qrParsed key=dc74373c… fallback=true` 证明
 *    **页面内 `fetch` 申请 unikey 是成功的**（同源、带网页 cookie、浏览器 UA）。
 *    ⇒ 请求通道没问题，缺的只是把结果送出来。
 *
 * ## 这一版怎么做
 *
 * 把整条链路搬进页面：注入后页面自己申请 unikey、自己按 1s 轮询 `client/login`，
 * 每次状态变化**主动**写 `document.title`。三处关键设计：
 *
 * - **页面上报表里带 `ts`**：保证 `title` 每拍都变，`onReceivedTitle` 必然回调，
 *   直接消灭故障 2；
 * - **App 不再调用 `evaluateJavascript` 取状态**：没有回调就没有挂起，
 *   `withTimeout` 那套兜底一并省掉；
 * - **unikey 与轮询都由页面发出**，与真实网页登录同源同栈，避免渠道风控。
 *
 * 授权成功后 cookie 由浏览器正常落进 [android.webkit.CookieManager]，直接读即可。
 *
 * ## 注入时机
 * [android.webkit.WebViewClient.onPageFinished]。页面会发生
 * `#/login → /` 两次导航，导航会清空 `window`，所以脚本靠
 * `window.__meloxQrBoot` 幂等，App 侧按「unikey 变了就重画」处理重复启动。
 */
internal object NeteaseQrBridge {

    /** 登录页。用真实域名登录页，保证 cookie / 同源 / Referer 都是网页那份。 */
    const val PAGE_URL = "https://music.163.com/#/login"

    /**
     * 二维码内容前缀。网易云官方登录模块把 unikey 直接拼在 `codekey=` 后面交给
     * QRCode.js 渲染，按同样规则拼出来的文本，画出的就是**网页上那一张码**。
     */
    const val QR_CONTENT_PREFIX = "http://music.163.com/login?codekey="

    /**
     * 安装状态机并启动。
     *
     * 幂等：靠 `window.__meloxQrBoot` 防重复启动（每次导航会重置 `window`，
     * 重跑一次只是换一张码，无害）。
     *
     * 上报表里 `stage` / `err` 是诊断用：`stage` 能区分卡在
     * `unikey` / `ready` / `poll-801` / `pollErr`，`err` 存放非 80x 状态码的响应原文
     * （风控码如 8821 会整段回在这里，不用再去猜）。
     */
    const val BOOTSTRAP_JS = """
(function(){
  if (window.__meloxQrBoot) return 1;
  window.__meloxQrBoot = true;
  var s = window.__meloxQr = { unikey:'', code:0, seen:0, stage:'boot', err:'', log:[] };
  function L(m){ try { s.log.push(String(m)); if (s.log.length > 4) s.log.shift(); } catch(e){} }
  function push(){
    try {
      document.title = 'MELOXQR:' + JSON.stringify({
        unikey: s.unikey, code: s.code, seen: s.seen, stage: s.stage, err: s.err,
        ts: Date.now(),
        href: String(location.href || '').slice(0, 60),
        log: s.log.slice(-3).join(' | ')
      });
    } catch(e){}
  }
  function post(url, body){
    return fetch(url, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body
    }).then(function(r){ return r.text(); });
  }
  function grabUnikey(t){
    try { var j = JSON.parse(t); if (j && j.unikey) return String(j.unikey); } catch(e){}
    var m = String(t || '').match(/"unikey"\s*:\s*"([^"]+)"/);
    return m ? m[1] : '';
  }
  function grabCode(t){
    var c = String(t || '').match(/"code"\s*:\s*([0-9]+)/);
    return c ? parseInt(c[1], 10) : 0;
  }
  function poll(){
    if (!s.unikey) return;
    var k = encodeURIComponent(s.unikey);
    post('/api/login/qrcode/client/login?key=' + k + '&type=1', 'key=' + k + '&type=1')
      .then(function(t){
        s.seen++;
        var c = grabCode(t);
        s.code = c;
        s.stage = 'poll-' + c;
        // 80x/88xx 都是终态：成功、过期、风控都到此为止，再轮询只会读到同一个码。
        if (c === 803 || c === 800 || c >= 8000) {
          if (c >= 8000) s.err = String(t || '').slice(0, 160);
          L('end ' + c);
          s.stopped = true;
          push();
          return;
        }
        push();
      })
      .catch(function(e){ s.stage = 'pollErr'; s.err = String(e); L('pollErr'); push(); });
  }
  // 下一次轮询的间隔：2.5s 基准 ± 0.6s 抖动。
  // 严格等间隔的请求序列本身就是一个机器特征 —— 真实页面的节奏从来不是精确的。
  // 抖动不牺牲体验（用户扫码后仍是秒级响应），只是把周期性抹掉。
  function schedulePoll(){
    var wait = 2500 + Math.floor(Math.random() * 1200) - 600;
    try {
      s.timer = setTimeout(function(){
        poll();
        if (!s.stopped) schedulePoll();
      }, wait);
    } catch(e){}
  }
  function start(){
    s.stage = 'unikey';
    push();
    post('/api/login/qrcode/unikey?type=1', 'type=1')
      .then(function(t){
        s.seen++;
        s.unikey = grabUnikey(t);
        if (s.unikey) {
          s.stage = 'ready';
          L('key ' + s.unikey.slice(0, 6));
          push();
          poll();
          schedulePoll();
        } else {
          s.stage = 'unikeyFail';
          s.err = String(t || '').slice(0, 160);
          L('noKey');
          push();
        }
      })
      .catch(function(e){ s.stage = 'unikeyErr'; s.err = String(e); L('unikeyErr'); push(); });
  }
  // 页面可能还在导航，等 load 之后再发，避免请求打在即将卸载的 document 上。
  if (document.readyState === 'complete') { setTimeout(start, 200); }
  else { window.addEventListener('load', function(){ setTimeout(start, 300); }); }
  return 1;
})();
"""

    /**
     * 诊断心跳（**不参与主流程**）：主流程只读页面推上来的 `title`。
     *
     * 保留它纯粹是为了对照——万一页面推送也失效，日志里至少能看出
     * `hook` / `stage` / `seen` 停在哪个值，而不是一片空白。
     */
    const val READ_JS = """
(function(){
  var s = window.__meloxQr || {};
  return JSON.stringify({
    unikey: s.unikey || '',
    code: s.code || 0,
    seen: s.seen || 0,
    stage: s.stage || '',
    err: String(s.err || '').slice(0, 120),
    boot: window.__meloxQrBoot === true,
    rs: document.readyState,
    href: String(location.href || '').slice(0, 60),
    log: (s.log || []).slice(-3).join(' | ')
  });
})();
"""

    /** [BOOTSTRAP_JS] 上报时用的前缀，用来把诊断结果和页面真实标题区分开。 */
    const val REPORT_PREFIX = "MELOXQR:"
}
