<script setup>
import { computed, onMounted, ref } from 'vue'
const user = ref(JSON.parse(localStorage.getItem('atoms-user') || 'null'))
const projects=ref([]), active=ref(null), prompt=ref(''), preview=ref(''), busy=ref(false), error=ref(''), mode=ref('login')
const form=ref({name:'',email:'',password:''})
const headers=()=>({'Content-Type':'application/json','X-User-Id':user.value?.id})
// Normalizes older fallback artifacts so existing saved versions remain interactive after upgrades.
function preparePreview(html){ return (html||'').replace("onclick='n.textContent=+n.textContent+1'", "onclick=\"document.getElementById('n').textContent=String(Number(document.getElementById('n').textContent)+1)\"") }
async function api(url, options={}) { const r=await fetch('/api'+url,{...options,headers:{...headers(),...(options.headers||{})}}); if(!r.ok) throw new Error((await r.json().catch(()=>({}))).message||'请求失败'); return r.status===204?null:r.json() }
async function auth(){ error.value=''; try { const u=await api('/auth/'+mode.value,{method:'POST',body:JSON.stringify(form.value)}); user.value=u;localStorage.setItem('atoms-user',JSON.stringify(u));await load() }catch(e){error.value=e.message} }
async function load(){ projects.value=await api('/projects'); if(projects.value.length&&!active.value) await choose(projects.value[0].id) }
async function choose(id){ active.value=await api('/projects/'+id); const x=await api('/projects/'+id+'/preview').catch(()=>null); preview.value=preparePreview(x?.html||''); prompt.value=active.value.prompt }
async function generate(){ if(!prompt.value.trim())return;busy.value=true;error.value='';try{ const r=await api('/projects/generate',{method:'POST',body:JSON.stringify({projectId:active.value?.id,prompt:prompt.value})});await load();await choose(r.projectId)}catch(e){error.value=e.message}finally{busy.value=false} }
async function version(v){ const x=await api('/projects/'+active.value.id+'/preview?version='+v);preview.value=preparePreview(x.html) }
async function remove(id){if(confirm('删除这个项目及其所有版本？')){await api('/projects/'+id,{method:'DELETE'});active.value=null;preview.value='';await load()}}
function logout(){localStorage.removeItem('atoms-user');user.value=null;active.value=null;projects.value=[]}
onMounted(()=>user.value&&load())
</script>

<template>
  <main v-if="!user" class="auth-page"><section class="auth-card"><div class="atom">✦</div><p class="eyebrow">ATOMS DEMO</p><h1>把一句想法，变成可体验的产品。</h1><p class="muted">DeepSeek 驱动 · 版本持久化 · 可视化预览</p><div class="tabs"><button :class="{selected:mode==='login'}" @click="mode='login'">登录</button><button :class="{selected:mode==='register'}" @click="mode='register'">创建账号</button></div><input v-if="mode==='register'" v-model="form.name" placeholder="你的名字"><input v-model="form.email" placeholder="邮箱" type="email"><input v-model="form.password" placeholder="密码（至少 6 位）" type="password"><p v-if="error" class="error">{{error}}</p><button class="primary wide" @click="auth">{{mode==='login'?'进入工作台':'开始构建'}}</button></section></main>
  <main v-else class="shell"><aside><div class="brand"><span>✦</span> atoms</div><button class="new" @click="active=null;preview='';prompt=''">＋ 新建应用</button><p class="label">你的应用</p><div class="project-list"><button v-for="p in projects" :key="p.id" class="project" :class="{active:p.id===active?.id}" @click="choose(p.id)"><span>{{p.title}}</span><small>{{p.versions||0}} versions</small><i @click.stop="remove(p.id)">×</i></button></div><div class="profile"><b>{{user.name}}</b><span>{{user.email}}</span><button @click="logout">退出</button></div></aside>
    <section class="workspace"><header><div><p class="eyebrow">{{active?'PROJECT / '+active.id:'NEW PROJECT'}}</p><h2>{{active?.title||'开始一个新构想'}}</h2></div><span class="status" :class="active?.status?.toLowerCase()">{{active?.status||'DRAFT'}}</span></header>
      <section class="composer"><label>你想构建什么？</label><textarea v-model="prompt" placeholder="例如：为独立咖啡馆创建一个可筛选菜单和预约桌位的落地页…" @keydown.meta.enter="generate"/><div><span>⌘ Enter 快速生成</span><button class="primary" :disabled="busy" @click="generate">{{busy?'智能体正在构建…':'✦ 生成应用'}}</button></div></section>
      <section class="stages"><div><b>01</b><span>理解需求</span></div><div><b>02</b><span>设计界面</span></div><div><b>03</b><span>生成并预览</span></div></section>
      <p v-if="error" class="error workspace-error">{{error}}</p><section v-if="active" class="versions"><span>版本历史</span><button v-for="g in active.generations" :key="g.id" @click="version(g.version)">v{{g.version}} · {{g.provider?.startsWith('DeepSeek ·')?'DeepSeek':'Local fallback'}}</button></section>
    </section>
    <section class="preview"><div class="previewbar"><span class="dot"></span><span class="dot"></span><span class="dot"></span><b>Live Preview</b><span v-if="active">{{active.status}}</span></div><iframe v-if="preview" :srcdoc="preview" sandbox="allow-scripts allow-forms" title="Generated application"/><div v-else class="empty"><div>✦</div><h3>你的作品将在这里诞生</h3><p>描述一个真实的产品需求，智能体会生成可交互网页。</p></div></section>
  </main>
</template>
