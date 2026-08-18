<script setup>
/**
 * Atoms Forge 工作台：登录、项目列表、Agent 计划/执行、iframe 预览。
 * 前端只调 /api，不直接请求 DeepSeek。
 */
import {computed, onBeforeUnmount, onMounted, ref} from 'vue';
import './workflow.css';

/** 当前登录用户；刷新页面后从 localStorage 恢复。 */
const user = ref(JSON.parse(localStorage.getItem('atoms-user') || 'null'));

/** 侧边栏项目列表。 */
const projects = ref([]);

/** 当前打开的项目详情（含 generations、agentRuns）。 */
const active = ref(null);

/** composer 里的自然语言需求。 */
const prompt = ref('');

/** iframe srcdoc 使用的 HTML 字符串。 */
const preview = ref('');

/** 计划/批准请求进行中，用于禁用按钮。 */
const busy = ref(false);

const error = ref('');

/** 预览中的 generations.version。 */
const selectedVersion = ref(null);

const form = ref({name: '', email: '', password: ''});
const authMode = ref('login');

/** CREATE | MODIFY | BUGFIX，随计划请求发给后端。 */
const changeType = ref('CREATE');

/** 同步生成路径留下的 trace；Agent 流程主要用 agentRun.trace。 */
const trace = ref([]);
const regression = ref(null);
const codeOpen = ref(false);
const code = ref('');

/** 当前 Agent 任务；轮询时整份替换。 */
const agentRun = ref(null);

/** 可编辑计划，批准前 PATCH 回后端。 */
const planDraft = ref('');

/** 轮询 GET /agent-runs/{id} 的 timer id。 */
let pollTimer = null;

/** 修改历史里展开的那一条 run。 */
const selectedRun = ref(null);

/**
 * 把后端 stage 映射到阶段条 01/02/03。
 * LOADING_CONTEXT=1, GENERATING=2, REGRESSION=3, COMPLETED=4。
 */
const stageRank = computed(() => {
  const stage = agentRun.value?.stage || '';
  if (stage === 'COMPLETED') {
    return 4;
  }
  if (stage === 'REGRESSION') {
    return 3;
  }
  if (stage === 'GENERATING') {
    return 2;
  }
  if (stage === 'LOADING_CONTEXT') {
    return 1;
  }
  return 0;
});

/** 演示身份：浏览器保存的 user.id，对应请求头 X-User-Id。 */
function headers() {
  return {
    'Content-Type': 'application/json',
    'X-User-Id': user.value?.id,
  };
}

/**
 * 修正本地 fallback 页里依赖全局 id=n 的 onclick，srcdoc 下会失效。
 * @param {string} html
 * @return {string}
 */
function preparePreview(html) {
  return (html || '').replace(
      "onclick='n.textContent=+n.textContent+1'",
      'onclick="document.getElementById(\'n\').textContent=' +
          'String(Number(document.getElementById(\'n\').textContent)+1)"');
}

/**
 * 调用后端 JSON API。url 从 /api 之后写起，例如 /projects。
 * @param {string} url
 * @param {RequestInit=} options
 */
async function api(url, options = {}) {
  const response = await fetch('/api' + url, {
    ...options,
    headers: {...headers(), ...(options.headers || {})},
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(
        body.detail || body.message || body.error ||
        `请求失败（HTTP ${response.status}）`);
  }
  return response.status === 204 ? null : response.json();
}

/** 登录或注册，成功后写入 localStorage 并加载项目列表。 */
async function auth() {
  error.value = '';
  try {
    const signedIn = await api('/auth/' + authMode.value, {
      method: 'POST',
      body: JSON.stringify(form.value),
    });
    user.value = signedIn;
    localStorage.setItem('atoms-user', JSON.stringify(signedIn));
    await load();
  } catch (e) {
    error.value = e.message;
  }
}

/** 刷新侧边栏；若还没有打开的项目，默认选第一项。 */
async function load() {
  projects.value = await api('/projects');
  if (projects.value.length && !active.value) {
    await choose(projects.value[0].id);
  }
}

/**
 * 打开项目：拉详情 + 最新预览 HTML。
 * @param {number} id
 */
async function choose(id) {
  active.value = await api('/projects/' + id);
  const artifact = await api('/projects/' + id + '/preview').catch(() => null);
  preview.value = preparePreview(artifact?.html);
  selectedVersion.value = artifact?.version || null;
  prompt.value = active.value.prompt;
  trace.value = [];
  regression.value = null;
  selectedRun.value = null;
}

/**
 * Agent 第 1 步：生成可编辑计划（此时不生成 HTML）。
 * 用 requestedPrompt 记住提交内容，避免 choose 把输入框还原成库里的旧 prompt。
 */
async function preparePlan() {
  if (!prompt.value.trim()) {
    return;
  }
  const requestedPrompt = prompt.value;
  busy.value = true;
  error.value = '';
  try {
    const run = await api('/projects/plan', {
      method: 'POST',
      body: JSON.stringify({
        projectId: active.value?.id,
        prompt: requestedPrompt,
        changeType: changeType.value,
      }),
    });
    agentRun.value = run;
    planDraft.value = run.plan;
    active.value = null;
    await load();
    await choose(run.project_id);
    prompt.value = requestedPrompt;
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}

/**
 * Agent 第 2/3 步：先保存编辑后的计划，再批准异步执行，并开始轮询。
 */
async function approvePlan() {
  if (!agentRun.value) {
    return;
  }
  busy.value = true;
  try {
    await api('/agent-runs/' + agentRun.value.id + '/plan', {
      method: 'PATCH',
      body: JSON.stringify({plan: planDraft.value}),
    });
    agentRun.value = await api(
        '/agent-runs/' + agentRun.value.id + '/approve', {method: 'POST'});
    watchRun();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}

/** 取消计划或执行中的任务（执行中为协作式取消，不强杀 HTTP）。 */
async function cancelRun() {
  if (!agentRun.value) {
    return;
  }
  agentRun.value = await api(
      '/agent-runs/' + agentRun.value.id + '/cancel', {method: 'POST'});
  stopWatching();
}

function stopWatching() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

/**
 * 拉一次任务状态。终态停止轮询；COMPLETED 时刷新预览并拆出回归摘要。
 */
async function refreshRun() {
  if (!agentRun.value) {
    return;
  }
  try {
    agentRun.value = await api('/agent-runs/' + agentRun.value.id);
    const done = ['COMPLETED', 'FAILED', 'CANCELLED'];
    if (done.includes(agentRun.value.status)) {
      stopWatching();
      if (agentRun.value.status === 'COMPLETED') {
        const text = agentRun.value.regression || '';
        regression.value = {
          status: text.split(':')[0] || 'DONE',
          summary: text,
        };
        await load();
        await choose(agentRun.value.project_id);
      }
    }
  } catch (e) {
    error.value = e.message;
    stopWatching();
  }
}

/** 立即刷新一次，再每 900ms 轮询，直到终态。 */
function watchRun() {
  stopWatching();
  refreshRun();
  pollTimer = setInterval(refreshRun, 900);
}

/**
 * 切换历史版本，只换 iframe，不调模型。
 * @param {number} v
 */
async function version(v) {
  const artifact =
      await api('/projects/' + active.value.id + '/preview?version=' + v);
  preview.value = preparePreview(artifact.html);
  selectedVersion.value = artifact.version;
}

/** 以纯文本拉取当前预览版本的 HTML，打开代码面板。 */
async function showCode() {
  if (!active.value) {
    return;
  }
  const response = await fetch(
      '/api/projects/' + active.value.id + '/code?version=' +
          selectedVersion.value,
      {headers: {'X-User-Id': user.value.id}});
  if (!response.ok) {
    error.value = '无法读取代码';
    return;
  }
  code.value = await response.text();
  codeOpen.value = true;
}

async function copyCode() {
  await navigator.clipboard.writeText(code.value);
  alert('代码已复制');
}

/** 下载当前预览版本的 ZIP（index.html + README）。 */
async function exportZip() {
  if (!active.value) {
    return;
  }
  const response = await fetch(
      '/api/projects/' + active.value.id + '/export?version=' +
          selectedVersion.value,
      {headers: {'X-User-Id': user.value.id}});
  if (!response.ok) {
    error.value = '导出失败';
    return;
  }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download =
      `atoms-project-${active.value.id}-v${selectedVersion.value}.zip`;
  link.click();
  URL.revokeObjectURL(url);
}

/**
 * 删除项目及其全部生成版本。
 * @param {number} id
 */
async function remove(id) {
  if (!confirm('删除这个项目及其所有版本？')) {
    return;
  }
  await api('/projects/' + id, {method: 'DELETE'});
  active.value = null;
  preview.value = '';
  await load();
}

/** 清空当前选择，切到 CREATE，准备一个新应用。 */
function newProject() {
  active.value = null;
  preview.value = '';
  prompt.value = '';
  changeType.value = 'CREATE';
  trace.value = [];
  regression.value = null;
  agentRun.value = null;
  selectedRun.value = null;
  stopWatching();
}

function logout() {
  localStorage.removeItem('atoms-user');
  user.value = null;
  active.value = null;
  projects.value = [];
}

onMounted(() => {
  if (user.value) {
    load();
  }
});
onBeforeUnmount(stopWatching);
</script>

<template>
  <!-- 未登录：注册 / 登录 -->
  <main v-if="!user" class="auth-page">
    <section class="auth-card">
      <div class="atom">✦</div>
      <p class="eyebrow">ATOMS FORGE</p>
      <h1>把一句想法，变成可体验的产品。</h1>
      <p class="muted">DeepSeek 驱动 · 版本持久化 · 代码可导出</p>
      <div class="tabs">
        <button
            :class="{selected: authMode === 'login'}"
            @click="authMode = 'login'">
          登录
        </button>
        <button
            :class="{selected: authMode === 'register'}"
            @click="authMode = 'register'">
          创建账号
        </button>
      </div>
      <input
          v-if="authMode === 'register'"
          v-model="form.name"
          placeholder="你的名字">
      <input v-model="form.email" placeholder="邮箱" type="email">
      <input
          v-model="form.password"
          placeholder="密码（至少 6 位）"
          type="password">
      <p v-if="error" class="error">{{ error }}</p>
      <button class="primary wide" @click="auth">
        {{ authMode === 'login' ? '进入工作台' : '开始构建' }}
      </button>
    </section>
  </main>

  <!-- 已登录：侧栏 + 编排 + Live Preview -->
  <main v-else class="shell">
    <aside>
      <div class="brand">
        <span>✦</span> atoms
      </div>
      <button class="new" @click="newProject">＋ 新建应用</button>
      <p class="label">你的应用</p>
      <div class="project-list">
        <button
            v-for="p in projects"
            :key="p.id"
            class="project"
            :class="{active: p.id === active?.id}"
            @click="choose(p.id)">
          <span>{{ p.title }}</span>
          <small>{{ p.versions || 0 }} versions</small>
          <i @click.stop="remove(p.id)">×</i>
        </button>
      </div>
      <div class="profile">
        <b>{{ user.name }}</b>
        <span>{{ user.email }}</span>
        <button @click="logout">退出</button>
      </div>
    </aside>

    <section class="workspace">
      <header>
        <div>
          <p class="eyebrow">
            {{ active ? 'PROJECT / ' + active.id : 'NEW PROJECT' }}
          </p>
          <h2>{{ active?.title || '开始一个新构想' }}</h2>
        </div>
        <span class="status" :class="active?.status?.toLowerCase()">
          {{ active?.status || 'DRAFT' }}
        </span>
      </header>

      <section class="composer">
        <div class="composer-head">
          <label>本次改动</label>
          <select
              v-model="changeType"
              :disabled="agentRun && agentRun.status === 'RUNNING'">
            <option value="CREATE">创建应用</option>
            <option value="MODIFY" :disabled="!active">
              增量修改（保留功能）
            </option>
            <option value="BUGFIX" :disabled="!active">
              修复 Bug（执行回归检查）
            </option>
          </select>
        </div>
        <textarea
            v-model="prompt"
            :disabled="agentRun && agentRun.status === 'RUNNING'"
            :placeholder="changeType === 'BUGFIX'
              ? '例如：手机号输入 abc 仍预约成功，请修复且不要影响菜单筛选。'
              : '描述想创建或修改的功能…'"
            @keydown.meta.enter="preparePlan"/>
        <div>
          <span>
            {{ active && changeType !== 'CREATE'
              ? '将携带当前代码与最近 8 条历史需求'
              : '先生成计划，再人工批准执行' }}
          </span>
          <button
              class="primary"
              :disabled="busy || (agentRun && agentRun.status === 'RUNNING')"
              @click="preparePlan">
            {{ busy ? '正在准备…' : '✦ 生成计划' }}
          </button>
        </div>
      </section>

      <section class="stages">
        <div :class="{stageActive: stageRank === 1, stageDone: stageRank > 1}">
          <b>01</b>
          <span>理解需求</span>
          <small>{{ stageRank > 0 ? '已完成' : '等待中' }}</small>
        </div>
        <div :class="{stageActive: stageRank === 2, stageDone: stageRank > 2}">
          <b>02</b>
          <span>设计页面</span>
          <small>{{ stageRank > 1 ? '已完成' : '等待中' }}</small>
        </div>
        <div :class="{stageActive: stageRank === 3, stageDone: stageRank > 3}">
          <b>03</b>
          <span>生成预览</span>
          <small>{{ stageRank > 2 ? '已完成' : '等待中' }}</small>
        </div>
      </section>

      <section v-if="agentRun" class="agent-flow">
        <div class="flow-title">
          <b>Agent Run #{{ agentRun.id }}</b>
          <span :class="'run-' + agentRun.status.toLowerCase()">
            {{ agentRun.stage }}
          </span>
        </div>
        <p v-if="agentRun.status === 'PLAN_READY'">
          请审阅或编辑计划，批准后才会调用模型生成代码。
        </p>
        <textarea
            v-if="agentRun.status === 'PLAN_READY'"
            v-model="planDraft"
            class="plan-editor"/>
        <ol class="flow-trace">
          <li
              v-for="item in (agentRun.trace || '').split('\n').filter(Boolean)"
              :key="item">
            {{ item }}
          </li>
        </ol>
        <div class="flow-actions" v-if="agentRun.status === 'PLAN_READY'">
          <button class="primary" :disabled="busy" @click="approvePlan">
            批准并执行
          </button>
          <button class="utility" @click="cancelRun">取消计划</button>
        </div>
        <div
            class="flow-actions"
            v-else-if="agentRun.status === 'RUNNING'">
          <button class="utility" @click="cancelRun">取消执行</button>
        </div>
        <p v-if="agentRun.status === 'FAILED'" class="error">
          任务失败，请修改计划或重新创建任务。
        </p>
      </section>

      <section v-if="trace.length" class="agent-log">
        <b>本次执行记录</b>
        <ol>
          <li v-for="item in trace" :key="item">{{ item }}</li>
        </ol>
      </section>
      <section
          v-if="regression"
          class="regression"
          :class="regression.status.toLowerCase()">
        <b>回归检查 · {{ regression.status }}</b>
        <span>{{ regression.summary }}</span>
      </section>
      <p v-if="error" class="error workspace-error">{{ error }}</p>

      <section v-if="active" class="versions">
        <span>版本历史</span>
        <button
            v-for="g in active.generations"
            :key="g.id"
            :class="{selectedVersion: g.version === selectedVersion}"
            @click="version(g.version)">
          v{{ g.version }} ·
          {{ g.provider?.startsWith('DeepSeek ·') ? 'DeepSeek' : 'Local fallback' }}
        </button>
        <span class="grow"/>
        <button class="utility" @click="showCode">查看代码</button>
        <button class="utility" @click="exportZip">ZIP 导出</button>
      </section>

      <section v-if="codeOpen" class="code-panel">
        <div>
          <b>生成代码</b>
          <span>
            <button @click="copyCode">复制</button>
            <button @click="codeOpen = false">关闭</button>
          </span>
        </div>
        <pre>{{ code }}</pre>
      </section>

      <section v-if="active?.agentRuns?.length" class="history-panel">
        <div class="history-title">
          <b>修改历史</b>
          <span>{{ active.agentRuns.length }} 次记录</span>
        </div>
        <button
            v-for="run in active.agentRuns"
            :key="run.id"
            class="history-item"
            :class="{historySelected: selectedRun?.id === run.id}"
            @click="selectedRun = selectedRun?.id === run.id ? null : run">
          <span class="history-dot"></span>
          <span class="history-main">
            <b>
              {{ run.change_type === 'BUGFIX' ? 'Bug 修复'
                : run.change_type === 'MODIFY' ? '增量修改'
                : '创建应用' }}
            </b>
            <small>{{ run.prompt }}</small>
          </span>
          <span class="history-status">{{ run.status }}</span>
        </button>
        <div v-if="selectedRun" class="history-detail">
          <p><b>实施计划</b></p>
          <pre>{{ selectedRun.plan }}</pre>
          <p><b>执行记录</b></p>
          <ol>
            <li
                v-for="line in (selectedRun.trace || '').split('\n').filter(Boolean)"
                :key="line">
              {{ line }}
            </li>
          </ol>
          <p v-if="selectedRun.regression">
            <b>回归结果：</b>{{ selectedRun.regression }}
          </p>
        </div>
      </section>
    </section>

    <section class="preview">
      <div class="previewbar">
        <span class="dot"></span>
        <span class="dot"></span>
        <span class="dot"></span>
        <b>Live Preview</b>
        <span v-if="selectedVersion">v{{ selectedVersion }}</span>
        <span v-if="active">{{ active.status }}</span>
      </div>
      <!-- sandbox：可跑脚本/表单，无 allow-same-origin，生成页拿不到登录态 -->
      <iframe
          v-if="preview"
          :srcdoc="preview"
          sandbox="allow-scripts allow-forms"
          title="Generated application"/>
      <div v-else class="empty">
        <div>✦</div>
        <h3>你的作品将在这里诞生</h3>
        <p>创建应用或选择历史项目，即可预览可交互版本。</p>
      </div>
    </section>
  </main>
</template>
