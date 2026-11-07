import * as THREE from 'three';

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x9bc8df);
scene.fog = new THREE.Fog(0x9bc8df, 24, 92);
const camera = new THREE.PerspectiveCamera(75, innerWidth / innerHeight, 0.1, 180);
camera.rotation.order = 'YXZ';
const renderer = new THREE.WebGLRenderer({ antialias: false, powerPreference: 'high-performance' });
renderer.setPixelRatio(Math.min(devicePixelRatio, 1.7));
renderer.setSize(innerWidth, innerHeight);
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.12;
document.querySelector('#game').prepend(renderer.domElement);

const hemi = new THREE.HemisphereLight(0xcce8ff, 0x66734b, 1.65); scene.add(hemi);
const sun = new THREE.DirectionalLight(0xfff1d1, 2.3); sun.position.set(-35, 55, 25); sun.castShadow = true;
sun.shadow.mapSize.set(1024,1024); sun.shadow.camera.left=-35;sun.shadow.camera.right=35;sun.shadow.camera.top=35;sun.shadow.camera.bottom=-35;scene.add(sun);
const sunMesh = new THREE.Mesh(new THREE.SphereGeometry(3,16,12),new THREE.MeshBasicMaterial({color:0xffe7a1}));scene.add(sunMesh);

const materials = {
 grass: new THREE.MeshLambertMaterial({color:0x6f9e48}),
 dirt: new THREE.MeshLambertMaterial({color:0x876044}),
 stone: new THREE.MeshLambertMaterial({color:0x858d8b}),
 wood: new THREE.MeshLambertMaterial({color:0x93633b}),
 leaves: new THREE.MeshLambertMaterial({color:0x477d43}),
 sand: new THREE.MeshLambertMaterial({color:0xd8c58a}),
 brick: new THREE.MeshLambertMaterial({color:0xa95f4b})
};
const blockDefs = [
  {id:'grass',name:'Grama',color:'#6f9e48',material:materials.grass},
  {id:'dirt',name:'Terra',color:'#876044',material:materials.dirt},
  {id:'stone',name:'Pedra',color:'#858d8b',material:materials.stone},
  {id:'wood',name:'Madeira',color:'#93633b',material:materials.wood},
  {id:'leaves',name:'Folhas',color:'#477d43',material:materials.leaves},
  {id:'sand',name:'Areia',color:'#d8c58a',material:materials.sand},
  {id:'brick',name:'Tijolos',color:'#a95f4b',material:materials.brick}
];
const cubeGeo = new THREE.BoxGeometry(1,1,1);
const blocks = new Map();
const key = (x,y,z)=>`${x},${y},${z}`;
const hash = (x,z)=>{const n=Math.sin(x*127.1+z*311.7)*43758.5453;return n-Math.floor(n)};
function heightAt(x,z){return Math.floor(2+Math.sin(x*.19)*1.3+Math.cos(z*.17)*1.1+Math.sin((x+z)*.11)*.8);}
function addBlock(x,y,z,type,save=true){
  const k=key(x,y,z);if(blocks.has(k))return false;
  const def=blockDefs.find(b=>b.id===type)||blockDefs[0];
  const mesh=new THREE.Mesh(cubeGeo,def.material);mesh.position.set(x,y,z);mesh.castShadow=true;mesh.receiveShadow=true;mesh.userData={x,y,z,type};
  scene.add(mesh);blocks.set(k,mesh);if(save)worldEdits.push({op:'add',x,y,z,type});return true;
}
function removeBlock(x,y,z,save=true){const k=key(x,y,z),m=blocks.get(k);if(!m)return false;scene.remove(m);blocks.delete(k);if(save)worldEdits.push({op:'remove',x,y,z});return true;}
const worldEdits=[];
function makeTree(x,z){const y=heightAt(x,z)+1;for(let i=0;i<4;i++)addBlock(x,y+i,z,'wood',false);for(let dy=2;dy<=4;dy++){for(let dx=-2;dx<=2;dx++)for(let dz=-2;dz<=2;dz++){if(Math.abs(dx)+Math.abs(dz)+(dy===4?1:0)<=3 && hash(x+dx*3+dy,z+dz*5)>.12)addBlock(x+dx,y+dy,z+dz,'leaves',false);}}}
for(let x=-24;x<=24;x++)for(let z=-24;z<=24;z++){
  const h=heightAt(x,z),beach=h<=1;
  for(let y=-2;y<=h;y++){const type=y===h?(beach?'sand':'grass'):(y>=h-2?'dirt':'stone');addBlock(x,y,z,type,false);}
  if(!beach&&hash(x,z)>.982&&Math.abs(x)+Math.abs(z)>7)makeTree(x,z);
}
// Small landmark: a stone ring and a timber marker
for(let i=0;i<12;i++){const a=i*Math.PI/6;addBlock(Math.round(Math.cos(a)*3),heightAt(0,0)+1,Math.round(Math.sin(a)*3),'stone',false);}
addBlock(0,heightAt(0,0)+1,0,'wood',false);addBlock(0,heightAt(0,0)+2,0,'wood',false);

const player={pos:new THREE.Vector3(0,heightAt(0,0)+2.2,7),vel:new THREE.Vector3(),yaw:0,pitch:0,onGround:false};
camera.position.copy(player.pos);
const keysDown={};let locked=false,paused=true,selected=0,sensitivity=.75,reach=6;
const inventory={grass:64,dirt:64,stone:48,wood:24,leaves:32,sand:32,brick:16};
const raycaster=new THREE.Raycaster();raycaster.far=reach;
const clock=new THREE.Clock();let elapsed=0,day=1,toastTimer=0;
const toastEl=document.querySelector('#toast');
function toast(msg){toastEl.textContent=msg;toastEl.classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>toastEl.classList.remove('show'),1500);}
function updateHotbar(){const bar=document.querySelector('#hotbar');bar.innerHTML='';blockDefs.slice(0,6).forEach((b,i)=>{const slot=document.createElement('button');slot.className='slot'+(i===selected?' selected':'');slot.innerHTML=`<span class="number">${i+1}</span><span class="block-icon" style="background:${b.color}"></span><span class="count">${inventory[b.id]??0}</span>`;slot.title=b.name;slot.onclick=()=>selectSlot(i);bar.append(slot);});}
function selectSlot(i){selected=(i+blockDefs.length)%blockDefs.length;updateHotbar();}
updateHotbar();
function openMenu(){paused=true;document.exitPointerLock?.();document.querySelector('#menu-overlay').classList.remove('hidden');document.querySelector('#hint').style.opacity=0;}
function closeMenu(){paused=false;document.querySelector('#menu-overlay').classList.add('hidden');document.querySelector('#start-overlay').classList.add('hidden');document.querySelector('#hint').style.opacity=1;try{renderer.domElement.requestPointerLock?.();}catch(error){locked=false;}}
document.querySelector('#start-button').onclick=()=>{closeMenu();};
document.querySelector('#resume').onclick=()=>{closeMenu();};
document.querySelector('#menu-button').onclick=()=>openMenu();
document.querySelector('#sensitivity').oninput=e=>{sensitivity=Number(e.target.value);document.querySelector('#sensitivity-value').value=sensitivity.toFixed(2);};
document.querySelector('#render-distance').oninput=e=>{reach=Number(e.target.value)*1.2;raycaster.far=reach;document.querySelector('#distance-value').value=e.target.value+' chunks';scene.fog.far=Number(e.target.value)*18;};
renderer.domElement.addEventListener('click',()=>{if(!paused&&!locked){try{renderer.domElement.requestPointerLock?.();}catch(error){/* Teclados e mouses em tablets podem não suportar Pointer Lock. */}}});
document.addEventListener('pointerlockchange',()=>{locked=document.pointerLockElement===renderer.domElement;if(locked){paused=false;document.querySelector('#hint').style.opacity=0;}});
document.addEventListener('mousemove',e=>{if(paused)return;if(!locked&&e.buttons===0&&e.movementX===0&&e.movementY===0)return;player.yaw-=e.movementX*.002*sensitivity;player.pitch-=e.movementY*.002*sensitivity;player.pitch=Math.max(-Math.PI/2+.02,Math.min(Math.PI/2-.02,player.pitch));camera.rotation.set(player.pitch,player.yaw,0);});
document.addEventListener('keydown',e=>{keysDown[e.code]=true;if(e.code.startsWith('Digit')){const n=Number(e.code.slice(5));if(n>=1&&n<=6)selectSlot(n-1);}if(e.code==='Escape'&&locked)openMenu();if(e.code==='Space')e.preventDefault();});
document.addEventListener('keyup',e=>keysDown[e.code]=false);
function playerIntersects(x,y,z){return player.pos.x+0.28>x-.5&&player.pos.x-.28<x+.5&&player.pos.y+0.85>y-.5&&player.pos.y-1.65<y+.5&&player.pos.z+.28>z-.5&&player.pos.z-.28<z+.5;}
function collideAt(pos){const minX=Math.floor(pos.x-.3),maxX=Math.floor(pos.x+.3),minY=Math.floor(pos.y-1.6),maxY=Math.floor(pos.y+.8),minZ=Math.floor(pos.z-.3),maxZ=Math.floor(pos.z+.3);for(let x=minX;x<=maxX;x++)for(let y=minY;y<=maxY;y++)for(let z=minZ;z<=maxZ;z++)if(blocks.has(key(x,y,z)))return true;return false;}
function movePlayer(dt){
  const speed=keysDown.ShiftLeft?8:5.2,forward=new THREE.Vector3(-Math.sin(player.yaw),0,-Math.cos(player.yaw)),right=new THREE.Vector3(Math.cos(player.yaw),0,-Math.sin(player.yaw)),wish=new THREE.Vector3();
  if(keysDown.KeyW)wish.add(forward);if(keysDown.KeyS)wish.sub(forward);if(keysDown.KeyD)wish.add(right);if(keysDown.KeyA)wish.sub(right);if(wish.lengthSq())wish.normalize();
  player.vel.x=wish.x*speed;player.vel.z=wish.z*speed;
  if(keysDown.Space&&player.onGround){player.vel.y=8.2;player.onGround=false;}
  player.vel.y-=22*dt;
  const nextX=player.pos.clone();nextX.x+=player.vel.x*dt;if(!collideAt(nextX))player.pos.x=nextX.x;
  const nextZ=player.pos.clone();nextZ.z+=player.vel.z*dt;if(!collideAt(nextZ))player.pos.z=nextZ.z;
  const nextY=player.pos.clone();nextY.y+=player.vel.y*dt;
  if(!collideAt(nextY)){player.pos.y=nextY.y;player.onGround=false;}else{if(player.vel.y<0)player.onGround=true;player.vel.y=0;}
  if(player.pos.y<-10){player.pos.set(0,heightAt(0,0)+3,7);player.vel.set(0,0,0);toast('Você voltou ao ponto inicial.');}
  camera.position.copy(player.pos);
  document.querySelector('#coords').textContent=`X ${Math.floor(player.pos.x)} · Y ${Math.floor(player.pos.y)} · Z ${Math.floor(player.pos.z)}`;
}
function targetBlock(){raycaster.setFromCamera(new THREE.Vector2(0,0),camera);const hits=raycaster.intersectObjects([...blocks.values()],false);return hits[0]||null;}
function mine(){const hit=targetBlock();if(!hit)return;const m=hit.object,{x,y,z,type}=m.userData;if(y<=-2){toast('Este bloco é parte da base do mundo.');return;}removeBlock(x,y,z);inventory[type]=(inventory[type]||0)+1;updateHotbar();toast('+'+blockDefs.find(b=>b.id===type).name);}
function place(){const hit=targetBlock();if(!hit)return;const normal=hit.face.normal.clone();const p=hit.object.position.clone().add(normal);const x=Math.round(p.x),y=Math.round(p.y),z=Math.round(p.z),type=blockDefs[selected].id;if((inventory[type]||0)<=0){toast('Você não tem mais '+blockDefs[selected].name.toLowerCase()+'.');return;}if(playerIntersects(x,y,z)){toast('Não dá para colocar um bloco em você.');return;}if(addBlock(x,y,z,type)){inventory[type]--;updateHotbar();}}
document.addEventListener('mousedown',e=>{if(paused)return;if(e.button===0)mine();if(e.button===2)place();});
document.addEventListener('contextmenu',e=>e.preventDefault());
function updateDay(dt){elapsed+=dt;const cycle=elapsed%360;const t=cycle/360;const angle=t*Math.PI*2-Math.PI/2;sun.position.set(Math.cos(angle)*45,Math.sin(angle)*55,22);sunMesh.position.copy(sun.position).multiplyScalar(.95);const daylight=Math.max(.12,Math.sin(angle)*.85+.25);sun.intensity=daylight*2.1;hemi.intensity=.45+daylight*1.1;const sky=new THREE.Color().setHSL(.56,.38,.2+daylight*.38);scene.background.copy(sky);scene.fog.color.copy(sky);const hours=Math.floor(t*24),mins=Math.floor((t*24-hours)*60);document.querySelector('#clock').textContent=String(hours).padStart(2,'0')+':'+String(mins).padStart(2,'0');const newDay=Math.floor(elapsed/360)+1;if(newDay!==day){day=newDay;document.querySelector('#day-label').textContent='Dia '+day;toast('Um novo dia começou.');}}
function animate(){requestAnimationFrame(animate);const dt=Math.min(clock.getDelta(),.04);if(!paused)movePlayer(dt);updateDay(dt);renderer.render(scene,camera);}
animate();
addEventListener('resize',()=>{camera.aspect=innerWidth/innerHeight;camera.updateProjectionMatrix();renderer.setSize(innerWidth,innerHeight);renderer.setPixelRatio(Math.min(devicePixelRatio,1.7));});
toast('Mundo gerado! Explore à vontade.');
