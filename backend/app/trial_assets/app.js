"use strict";
const $ = id => document.getElementById(id);
const BINDING = "zhiyu.warm-trial.binding.v1";
let token = "", conversation = null, binding = null, providers = [], busy = false;
let latestMessageId = null, needsConfirmation = false;
const status = text => { $("status").textContent = text; };

function readBinding() {
  const raw = localStorage.getItem(BINDING);
  if (!raw) return null;
  const value = JSON.parse(raw);
  if (!value || typeof value.provider_id !== "string" || typeof value.model !== "string" ||
      !value.profile || value.profile.mode !== "preset" || !["warm_v1", "warm_v2"].includes(value.profile.style)) {
    throw new Error("本页绑定信息无法读取，请保留记录并联系开发者。");
  }
  return value;
}

function writeBinding(value) {
  try { localStorage.setItem(BINDING, JSON.stringify(value)); }
  catch { throw new Error("本页无法保存恋人绑定，请允许浏览器本地存储后再试。"); }
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options, headers: {"Content-Type": "application/json", ...options.headers,
      ...(token ? {Authorization: `Bearer ${token}`} : {})},
    cache: "no-store", redirect: "error"
  });
  if (response.status === 204) return null;
  const value = await response.json();
  if (!response.ok) {
    if (response.status === 401 && token) {
      token = "";
      $("chat").hidden = true;
      $("entry").hidden = false;
    }
    throw Object.assign(new Error(typeof value.detail === "string" ? value.detail : "连接暂时未完成，请重试。"), {status: response.status});
  }
  return value;
}

function applyBinding() {
  $("preset-version").textContent = `恋人预设 · ${binding?.profile.style || "warm_v2"}`;
  if (binding) {
    $("provider").value = binding.provider_id;
    $("model").value = binding.model;
    $("role").value = binding.profile.role;
    $("name").value = binding.profile.name;
    $("user-name").value = binding.profile.user_name;
    $("binding-note").textContent = "已固定这位恋人的公司、模型与设定。输入原 API Key 继续聊天；断开或刷新都不会解除绑定。";
    $("connect").textContent = "继续相伴";
  }
  for (const id of ["provider", "model", "role", "name", "user-name"]) $(id).disabled = !!binding || busy;
}

function setBusy(value) {
  busy = value;
  for (const id of ["connect", "disconnect", "key", "message"]) $(id).disabled = value;
  $("send").disabled = value || needsConfirmation;
  applyBinding();
}

function chooseModel() {
  if (!binding) $("model").value = providers.find(p => p.id === $("provider").value)?.default_model || "";
}

function profile() {
  return binding?.profile || {mode: "preset", style: "warm_v2", role: $("role").value,
    name: $("name").value.trim(), user_name: $("user-name").value.trim(), instructions: ""};
}

function sameProfile(a, b) {
  return ["mode", "style", "role", "name", "user_name", "instructions"].every(k => a?.[k] === b?.[k]);
}

function bubble(role, text) {
  const node = document.createElement("div");
  node.className = `bubble ${role === "user" ? "user" : "assistant"}`;
  const author = document.createElement("small");
  author.textContent = role === "user" ? "你" : (binding?.profile.name || "小语");
  const body = document.createElement("span");
  body.textContent = text;
  node.append(author, body);
  $("messages").append(node);
  $("empty").hidden = true;
  $("messages").scrollTop = $("messages").scrollHeight;
}

async function loadHistory() {
  const result = await api(`/api/conversations/${conversation.id}/history?limit=100`);
  $("messages").replaceChildren();
  $("empty").hidden = false;
  for (const item of result.messages) bubble(item.role, item.content);
  latestMessageId = result.messages.at(-1)?.id || null;
  if (result.has_more) status("已显示最近的聊天，较早原话仍保存在同一段聊天中。");
}

async function connect(event) {
  event.preventDefault();
  if (busy) return;
  setBusy(true);
  status("正在连接…");
  const key = $("key").value.trim();
  try {
    const identity = {provider_id: $("provider").value, model: $("model").value.trim()};
    const wanted = profile();
    const auth = await api("/api/auth/login", {method: "POST", body: JSON.stringify({...identity, api_key: key})});
    token = auth.token;
    $("key").value = "";
    const existing = await api("/api/conversations");
    if (existing.length > 1) throw new Error("此连接有多段旧记录，请联系开发者核对；本页不会另建聊天。");
    if (binding?.conversation_id && existing[0]?.id !== binding.conversation_id) {
      throw new Error("这个 Key 无法继续已绑定的聊天，请输入原 API Key。本页不会创建另一段关系。");
    }
    if (existing.length && (!sameProfile(existing[0].companion_profile, wanted) || existing[0].model !== auth.model)) {
      throw new Error("此 Key 已有另一份固定设定，本页不会替换；请在原应用继续。");
    }
    const chosen = {provider_id: auth.provider_id, model: auth.model, profile: wanted,
      ...(binding?.conversation_id ? {conversation_id: binding.conversation_id} : {})};
    writeBinding(chosen);
    binding = chosen;
    conversation = existing[0] || await api("/api/conversations", {method: "POST",
      body: JSON.stringify({title: "温柔相伴", model: auth.model, use_rag: true, companion_profile: wanted})});
    binding = {...chosen, conversation_id: conversation.id};
    writeBinding(binding);
    status("");
    await loadHistory();
    needsConfirmation = false;
    $("chat-title").textContent = wanted.name || "小语";
    $("entry").hidden = true;
    $("chat").hidden = false;
    $("message").focus();
  } catch (error) {
    if (token) {
      try { await api("/api/auth/session", {method: "DELETE"}); }
      catch { status("连接未完成；会话将自行过期，请重新连接。"); }
    }
    token = "";
    status(error.message || "连接未完成，请重试。");
  } finally { setBusy(false); }
}

async function send(event) {
  event.preventDefault();
  if (busy || needsConfirmation || !token || !conversation) return;
  const content = $("message").value.trim();
  if (!content) return;
  setBusy(true);
  status("正在听你说…");
  const previousId = latestMessageId;
  try {
    const result = await api(`/api/conversations/${conversation.id}/messages`, {
      method: "POST", body: JSON.stringify({content})
    });
    bubble("user", result.user_message.content);
    bubble("assistant", result.assistant_message.content);
    latestMessageId = result.assistant_message.id;
    $("message").value = "";
    status("");
  } catch (error) { await recoverSend(content, previousId, error); }
  finally { setBusy(false); $("message").focus(); }
}

async function recoverSend(content, previousId, error) {
  try {
    const page = await api(`/api/conversations/${conversation.id}/history?limit=100`);
    const user = page.messages.at(-2), assistant = page.messages.at(-1);
    if (assistant?.id !== previousId && user?.role === "user" && user.content === content && assistant?.role === "assistant") {
      await loadHistory();
      $("message").value = "";
      status("回复已经保存，已恢复到这段聊天。");
      return;
    }
    await loadHistory();
    needsConfirmation = !error.status;
    status(needsConfirmation ? "暂时无法确认回复是否仍在处理中，请稍后重新连接核对；文字已保留。" : error.message);
  } catch {
    needsConfirmation = true;
    status("暂时无法确认这句是否已保存，请先重新连接核对，避免重复发送。");
  }
}

async function disconnect() {
  if (busy) return;
  setBusy(true);
  try {
    if (token) await api("/api/auth/session", {method: "DELETE"});
    token = "";
    $("chat").hidden = true;
    $("entry").hidden = false;
    status("已断开连接，恋人绑定与聊天保留。");
  } catch (error) { status(error.message || "断开未完成，请重试。"); }
  finally { setBusy(false); }
}

async function initialize() {
  try {
    binding = readBinding();
    providers = await api("/api/providers");
    $("provider").replaceChildren();
    for (const provider of providers) {
      const option = document.createElement("option");
      option.value = provider.id;
      option.textContent = provider.name;
      $("provider").append(option);
    }
    if (binding && !providers.some(p => p.id === binding.provider_id)) throw new Error("已绑定的 AI 公司暂不可用，请联系开发者。");
    chooseModel();
    setBusy(false);
    $("connect-form").addEventListener("submit", connect);
    $("message-form").addEventListener("submit", send);
    $("provider").addEventListener("change", chooseModel);
    $("disconnect").addEventListener("click", disconnect);
  } catch (error) { status(error.message || "页面未能初始化，请刷新重试。"); }
}
initialize();
