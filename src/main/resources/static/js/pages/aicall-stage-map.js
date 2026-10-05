(() => {
    'use strict';
    document.querySelectorAll('[data-stage-map]').forEach((map,index)=>{
        const templates=new Map([...map.querySelectorAll('[data-stage]')].map(n=>[n.dataset.stage,n]));
        const viewport=map.querySelector('.stage-map-viewport'),canvas=map.querySelector('.stage-map-canvas'),space=map.querySelector('.stage-map-space'),svg=map.querySelector('svg');
        const tree=window.AiCallBranchTree;
        const forest=tree.fromDependencies([...templates].map(([code,n])=>({code,prerequisites:(n.dataset.prerequisites||'').split(',').filter(Boolean)})));
        const {nodes:boxes,edges,width,height}=tree.layout(forest,{minHeight:viewport.clientHeight,getHeight:code=>templates.get(code).offsetHeight});
        const cards=new Map();
        boxes.forEach(box=>{const n=templates.get(box.code).cloneNode(true);n.dataset.occurrence=box.id;n.style.left=`${box.x}px`;n.style.top=`${box.y}px`;canvas.append(n);cards.set(box.id,n);});
        templates.forEach(n=>n.remove());
        canvas.style.width=`${width}px`;canvas.style.height=`${height}px`;svg.setAttribute('viewBox',`0 0 ${width} ${height}`);
        const ns='http://www.w3.org/2000/svg';const make=(tag,attrs)=>{const e=document.createElementNS(ns,tag);Object.entries(attrs).forEach(([k,v])=>e.setAttribute(k,v));return e;};
        const id=`stage-arrow-${index}`,defs=make('defs',{}),marker=make('marker',{id,viewBox:'0 0 10 10',refX:9,refY:5,markerWidth:10,markerHeight:10,markerUnits:'userSpaceOnUse',orient:'auto'});
        marker.append(make('path',{d:'M 0 1 L 9 5 L 0 9 Z',fill:'context-stroke'}));defs.append(marker);svg.append(defs);
        const byId=new Map(boxes.map(b=>[b.id,b]));
        edges.forEach(edge=>{const p=make('path',{d:tree.connector(byId.get(edge.source),byId.get(edge.target)),'marker-end':`url(#${id})`});edge.path=p;p.classList.toggle('is-locked',cards.get(edge.target).dataset.status==='locked');p.classList.toggle('is-passed',cards.get(edge.source).dataset.status==='passed');svg.append(p);});
        let scale=1,selected;
        const zoom=n=>{scale=Math.max(.6,Math.min(1.5,n));canvas.style.transform=`scale(${scale})`;space.style.width=`${width*scale}px`;space.style.height=`${height*scale}px`;};
        const reveal=n=>viewport.scrollTo({left:Math.max(0,n.offsetLeft*scale-32),top:Math.max(0,(n.offsetTop+n.offsetHeight/2)*scale-viewport.clientHeight/2),behavior:'auto'});
        function renderStory(card){
            const list=map.querySelector('[data-stage-story-steps]');
            const steps=[...card.querySelectorAll('[data-story-code]')];
            map.querySelector('[data-stage-story-title]').textContent=`Diễn biến: ${card.dataset.title}`;
            list.replaceChildren();
            steps.forEach((step,index)=>{
                const d=step.dataset,li=document.createElement('li'),item=document.createElement('div');
                item.className='stage-story-step';
                if(d.storyEnding==='true')item.classList.add('is-ending');
                item.title=d.storySummary||d.storyTitle;
                const icon=document.createElement('i');icon.className=d.storyIcon||'fa-solid fa-comment';icon.setAttribute('aria-hidden','true');
                const title=document.createElement('span');title.textContent=d.storyTitle;
                const number=document.createElement('small');number.textContent=d.storyEnding==='true'?'Kết thúc':`Bước ${index+1}`;
                if(d.storySpeaker)number.textContent+=` · ${d.storySpeaker}`;
                item.append(icon,title,number);li.append(item);list.append(li);
            });
            map.querySelector('.stage-story-viewport').scrollLeft=0;
        }
        function select(occurrence,scroll=false){
            const card=cards.get(occurrence);if(!card)return;selected=occurrence;
            cards.forEach((n,c)=>{n.classList.toggle('is-selected',c===occurrence);n.querySelector('[data-stage-select]').setAttribute('aria-pressed',String(c===occurrence));});
            edges.forEach(e=>e.path.classList.toggle('is-selected',e.target===occurrence||e.source===occurrence));
            const d=card.dataset,locked=d.status==='locked',reviewer=map.dataset.reviewer==='true';
            renderStory(card);
            document.querySelectorAll(`[data-stage-selected-title][data-scenario="${map.dataset.scenario}"]`).forEach(n=>{n.textContent=d.title;});
            document.querySelectorAll(`[data-stage-start][data-scenario="${map.dataset.scenario}"]`).forEach(a=>{a.hidden=locked;a.href=d.resume?`/conversation/${d.resume}`:`/scenario/${map.dataset.scenario}/start?stage=${encodeURIComponent(d.stage)}`;const actionLabel=d.resume?'Tiếp tục':reviewer?'Kiểm tra màn':d.status==='passed'?'Luyện lại':'Bắt đầu màn';const label=a.querySelector('[data-stage-start-label]');if(label)label.textContent=actionLabel;a.setAttribute('aria-label',`${actionLabel}: ${d.title}`);a.title=`${actionLabel}: ${d.title}`;});
            document.querySelectorAll(`[data-stage-sample][data-scenario="${map.dataset.scenario}"]`).forEach(a=>{a.href=`/scenario/${map.dataset.scenario}/sample?stage=${encodeURIComponent(d.stage)}`;a.setAttribute('aria-label',`Xem hội thoại mẫu: ${d.title}`);a.title=`Xem hội thoại mẫu: ${d.title}`;});
            if(scroll)reveal(card);
        }
        cards.forEach((n,c)=>n.querySelector('[data-stage-select]').addEventListener('click',()=>select(c)));
        const recommended=[...cards].find(([,n])=>n.dataset.stage===map.dataset.recommended)?.[0];
        const initial=[...cards].find(([,n])=>n.dataset.stage===map.dataset.selected)?.[0]||recommended;
        map.querySelectorAll('[data-stage-zoom]').forEach(b=>b.addEventListener('click',()=>zoom(scale+(b.dataset.stageZoom==='in'?.15:-.15))));
        zoom(1);select(initial);reveal(cards.get(selected));
    });
    if(location.hash==='#case-preparation-heading'){
        const revealPreparation=()=>requestAnimationFrame(()=>document.getElementById('case-preparation-heading')?.scrollIntoView({block:'start'}));
        if(document.readyState==='complete')revealPreparation();
        else window.addEventListener('load',revealPreparation,{once:true});
    }
})();
