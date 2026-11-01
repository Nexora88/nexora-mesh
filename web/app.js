const $=s=>document.querySelector(s),$$=s=>[...document.querySelectorAll(s)];
const KEY='nexora-mesh-chat-v2';let deferredInstall=null,view='chats',filter='all';
let state={contacts:[],drafts:{},selected:null,relayOptIn:false};
try{state={...state,...JSON.parse(localStorage.getItem(KEY)||'{}')}}catch{}
const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const persist=()=>localStorage.setItem(KEY,JSON.stringify(state));
function active(){return state.contacts.find(c=>c.id===state.selected)||null}
function initials(s){return String(s||'NM').trim().slice(0,2).toUpperCase()}
function render(){
 const q=($('#search')?.value||'').toLowerCase();const list=$('#chat-list');const contacts=state.contacts.filter(c=>(!q||c.name.toLowerCase().includes(q))&&(filter!=='groups'||c.group));
 list.innerHTML=contacts.length?contacts.map(c=>'<button class="chat-item '+(c.id===state.selected?'active':'')+'" data-contact="'+esc(c.id)+'"><span class="avatar">'+esc(initials(c.name))+'</span><span class="chat-meta"><strong>'+esc(c.name)+'</strong><small>'+esc(c.group?'Grup sohbeti':'Yerel kişi · '+(c.fingerprint?'QR kaydı':'henüz eşleştirilmedi'))+'</small></span><span class="chat-time">LOCAL</span></button>').join(''):'<div class="welcome"><p>Henüz sohbet yok. ＋ düğmesiyle kişi ekle.</p></div>';
 $$('[data-contact]').forEach(b=>b.addEventListener('click',()=>{state.selected=b.dataset.contact;persist();render();if(innerWidth<=680)$('.app').classList.add('show-main')}));
 const c=active();$('#active-name').textContent=c?c.name:'Nexora Mesh';$('#active-avatar').textContent=c?initials(c.name):'NM';$('#active-presence').textContent=c?(c.fingerprint?'QR kaydı var · bağlantı doğrulanmadı':'Yerel kişi · cihaz eşleşmedi'):'Özel ve yerel iletişim alanı';
 $('#message-input').disabled=!c;$('#message-input').value=c?(state.drafts[c.id]||''):'';$('#send-btn').disabled=!c||!($('#message-input').value.trim());$('#message-input').placeholder=c?'Mesaj taslağını yaz…':'Önce bir sohbet seç…';
 $('#compose-status').textContent=c?'Bu cihazda saklanan taslak':'Mesaj alanı pasif';
 $('#messages').innerHTML=c?'<div class="welcome"><div class="welcome-mark">'+esc(initials(c.name))+'</div><h2>'+esc(c.name)+'</h2><p>Bu sohbet için taslak alanı hazır. Gerçek mesaj gönderimi ve teslimat onayı native bağlantı doğrulanana kadar kapalıdır.</p><span class="eyebrow">LOCAL PREVIEW · NOT DELIVERED</span></div>':'<div class="welcome"><div class="welcome-mark">N</div><h2>Bağlantını kur, konuşmaya başla.</h2><p>Bir kişi seç veya cihazını QR kimliğiyle tanıt. Gerçek aktarım yalnızca doğrulanmış native bağlantı kurulduğunda kullanılabilir.</p><button class="primary-btn" id="welcome-add">Cihaz tanıt</button></div>';
 $('#welcome-add')?.addEventListener('click',openDialog);$('#chat-info').onclick=()=>alert(c?'QR kaydı: '+(c.fingerprint?'mevcut':'yok')+'\nGüven doğrulaması: yapılmadı':'Henüz sohbet seçilmedi.');
 $('#relay-toggle').checked=!!state.relayOptIn;
}
function switchView(next){view=next;$$('.view').forEach(v=>v.hidden=v.id!=='view-'+next);$$('.rail-btn').forEach(b=>b.classList.toggle('active',b.dataset.view===next));$('#sidebar-title').textContent=({chats:'Sohbetler',devices:'Cihazlar',relay:'Mesh',settings:'Ayarlar'})[next]||'Sohbetler';if(innerWidth<=680){if(next==='chats'){$('.app').classList.remove('show-main')}else{$('.app').classList.add('show-main')}}}
$$('.rail-btn').forEach(b=>b.addEventListener('click',()=>switchView(b.dataset.view)));
function openDialog(){const d=$('#contact-dialog');if(d.showModal)d.showModal();else switchView('devices')}
function addContact(name,qr,feedback){name=name.trim();qr=qr.trim();if(!name){feedback.textContent='Cihaz adı gerekli.';return false}if(qr&&!qr.startsWith('NEXORAMESH:')){feedback.textContent='QR payload NEXORAMESH: ile başlamalı.';return false}const id=crypto.randomUUID?crypto.randomUUID():String(Date.now());state.contacts.push({id,name,fingerprint:qr,group:false,createdAt:Date.now(),verified:false});state.selected=id;persist();feedback.textContent='Yerel kişi kaydedildi. Cihaz doğrulaması yapılmadı.';switchView('chats');render();return true}
$('#new-chat').addEventListener('click',openDialog);$('#welcome-add')?.addEventListener('click',openDialog);$('#search').addEventListener('input',render);
$$('.filter').forEach(b=>b.addEventListener('click',()=>{filter=b.dataset.filter;$$('.filter').forEach(x=>x.classList.toggle('active',x===b));render()}));
$('#quick-save').addEventListener('click',()=>{if(addContact($('#quick-name').value,$('#quick-qr').value,$('#dialog-feedback'))){$('#contact-dialog').close();$('#quick-name').value='';$('#quick-qr').value=''}});
$('#contact-dialog').addEventListener('click',e=>{if(e.target===$('#contact-dialog'))$('#contact-dialog').close()});
$('#save-contact').addEventListener('click',()=>addContact($('#contact-name').value,$('#contact-fingerprint').value,$('#contact-feedback')));
$('#clear-contact').addEventListener('click',()=>{$('#contact-name').value='';$('#contact-fingerprint').value='';$('#contact-feedback').textContent=''});
$('#message-input').addEventListener('input',()=>{const c=active();if(!c)return;state.drafts[c.id]=$('#message-input').value;persist();$('#send-btn').disabled=!$('#message-input').value.trim();$('#compose-status').textContent='Taslak bu cihazda kaydedildi · gönderilmedi'});
$('#send-btn').addEventListener('click',()=>{$('#compose-status').textContent='Gönderilmedi: gerçek radyo köprüsü ve doğrulanmış native bağlantı yok. Taslak bu cihazda tutuluyor.'});
$('#security-details').addEventListener('click',()=>alert('Bu web arayüzü yerel önizleme modundadır. Mesajlar Bluetooth veya internet üzerinden gönderilmez. Uçtan uca güvenlik ve teslimat durumu native katmanda ayrıca doğrulanmalıdır.'));
$('#more-actions').addEventListener('click',()=>switchView('settings'));
$('#relay-toggle').addEventListener('change',e=>{state.relayOptIn=e.target.checked;persist();$('#relay-feedback').textContent='Tercih kaydedildi. Web arayüzü relay aktarımı yapmaz.'});
$('#export-data').addEventListener('click',()=>{const blob=new Blob([JSON.stringify(state,null,2)],{type:'application/json'});const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download='nexora-mesh-local-data.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);$('#settings-feedback').textContent='Yerel veriler dışa aktarıldı.'});
$('#clear-data').addEventListener('click',()=>{if(confirm('Bu tarayıcıdaki kişi ve taslak kayıtları silinsin mi?')){localStorage.removeItem(KEY);state={contacts:[],drafts:{},selected:null,relayOptIn:false};render();$('#settings-feedback').textContent='Yerel veriler silindi.'}});
$('#attach-btn').addEventListener('click',()=>$('#file-input').click());$('#file-input').addEventListener('change',()=>{const f=$('#file-input').files[0];if(f)$('#compose-status').textContent='Seçilen dosya yalnızca yerel önizleme: '+f.name+' · gönderim kapalı'});
$('#emoji-btn').addEventListener('click',()=>{$('#message-input').value+='🙂';$('#message-input').dispatchEvent(new Event('input'))});
window.addEventListener('beforeinstallprompt',e=>{e.preventDefault();deferredInstall=e;$('#install-btn').hidden=false});
$('#install-btn').addEventListener('click',async()=>{if(!deferredInstall)return;deferredInstall.prompt();await deferredInstall.userChoice;deferredInstall=null;$('#install-btn').hidden=true});
const desktopInvoke=window.__TAURI__?.core?.invoke,desktopStatus=$('#desktop-radio-status'),desktopList=$('#desktop-device-list');
async function desktopCall(command,args){if(!desktopInvoke){if(desktopStatus)desktopStatus.textContent='Native BLE yalnızca kurulu masaüstü uygulamasında kullanılabilir.';return null}try{return await desktopInvoke(command,args)}catch(e){desktopStatus.textContent='BLE hatası: '+String(e);return null}}
$('#desktop-scan').addEventListener('click',async()=>{const r=await desktopCall('start_ble_discovery');if(r)desktopStatus.textContent=String(r);refreshDevices()});
$('#desktop-refresh').addEventListener('click',refreshDevices);$('#desktop-stop').addEventListener('click',async()=>{await desktopCall('stop_ble_discovery');desktopStatus.textContent='Tarama durduruldu.'});
async function refreshDevices(){const ds=await desktopCall('nearby_ble_devices');if(!ds)return;desktopList.innerHTML=ds.length?ds.map(d=>'<div class="device-row">'+esc(d.name)+' · '+esc(d.id)+'</div>').join(''):'<div class="device-row">Cihaz bulunamadı.</div>'}
if(!desktopInvoke)desktopStatus.textContent='Tarayıcı/PWA modunda. Gerçek BLE taraması için masaüstü uygulaması gerekir.';
if('serviceWorker'in navigator)window.addEventListener('load',()=>navigator.serviceWorker.register('./sw.js').catch(()=>{}));
render();