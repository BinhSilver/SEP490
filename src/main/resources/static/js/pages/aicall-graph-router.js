/* Orthogonal routing through the space between cards. Shared stems are allowed. */
(function (scope) {
    'use strict';
    function route(rectangles, edges, width, height) {
        const boxes = new Map(rectangles.map(r => [r.code,r]));
        const obstacles = rectangles.map(r => ({l:r.x-10,r:r.x+r.width+10,t:r.y-10,b:r.y+r.height+10}));
        const xs = [...new Set([12,width-12,...rectangles.flatMap(r => [r.x-24,r.x+r.width+24])])].sort((a,b)=>a-b);
        const ys = [...new Set([12,height-12,...rectangles.flatMap(r => [r.y-24,r.y+r.height/2,r.y+r.height+24])])].sort((a,b)=>a-b);
        const nx=xs.length, ny=ys.length, used=[], cache=new Map(), result=new Array(edges.length);
        const point=id=>[xs[id%nx],ys[Math.floor(id/nx)]];
        function blocked(a,b) {
            const key=Math.min(a,b)+':'+Math.max(a,b);
            if(cache.has(key))return cache.get(key);
            const [x1,y1]=point(a),[x2,y2]=point(b);
            const hit=obstacles.some(r => y1===y2
                ? y1>r.t && y1<r.b && Math.max(x1,x2)>r.l && Math.min(x1,x2)<r.r
                : x1>r.l && x1<r.r && Math.max(y1,y2)>r.t && Math.min(y1,y2)<r.b);
            cache.set(key,hit);return hit;
        }
        class Heap {
            constructor(){this.a=[];}
            push(v){let i=this.a.length;this.a.push(v);while(i){const p=(i-1)>>1;if(this.a[p][0]<=v[0])break;this.a[i]=this.a[p];i=p;}this.a[i]=v;}
            pop(){const top=this.a[0],last=this.a.pop();if(this.a.length){let i=0;while(i*2+1<this.a.length){let c=i*2+1;if(c+1<this.a.length&&this.a[c+1][0]<this.a[c][0])c++;if(this.a[c][0]>=last[0])break;this.a[i]=this.a[c];i=c;}this.a[i]=last;}return top;}
        }
        const ordered=edges.map((e,index)=>({...e,index})).sort((a,b)=>{
            const af=boxes.get(a.source),at=boxes.get(a.target),bf=boxes.get(b.source),bt=boxes.get(b.target);
            return Number(at.x<=af.x)-Number(bt.x<=bf.x) || Math.abs(at.x-af.x)-Math.abs(bt.x-bf.x);
        });
        for(const edge of ordered) {
            const from=boxes.get(edge.source),to=boxes.get(edge.target);
            if(!from||!to)continue;
            const back=to.x<=from.x;
            const start=[from.x+from.width+24,from.y+from.height/2];
            const end=[back?to.x+to.width+24:to.x-24,to.y+to.height/2];
            const sid=ys.indexOf(start[1])*nx+xs.indexOf(start[0]),tid=ys.indexOf(end[1])*nx+xs.indexOf(end[0]);
            const heap=new Heap(),dist=new Map(),parent=new Map();
            const initial=sid*3;dist.set(initial,0);heap.push([0,initial]);let found;
            while(heap.a.length) {
                const [cost,key]=heap.pop();if(cost!==dist.get(key))continue;
                const id=Math.floor(key/3),dir=key%3;
                if(id===tid){found=key;break;}
                const x=id%nx,y=Math.floor(id/nx);
                const neighbors=[x? id-1:-1,x+1<nx?id+1:-1,y?id-nx:-1,y+1<ny?id+nx:-1];
                for(const next of neighbors) {
                    if(next<0||blocked(id,next))continue;
                    const a=point(id),b=point(next),nd=a[1]===b[1]?1:2;
                    let extra=Math.abs(a[0]-b[0])+Math.abs(a[1]-b[1])+(dir&&dir!==nd?28:0);
                    for(const s of used) {
                        if(s.source===edge.source||s.target===edge.target)continue;
                        const horizontal=nd===1;
                        const opposite=s.a[1]!==s.b[1];
                        if(horizontal===opposite) {
                            const h=horizontal?[a,b]:[s.a,s.b],v=horizontal?[s.a,s.b]:[a,b];
                            if(v[0][0]>=Math.min(h[0][0],h[1][0])&&v[0][0]<=Math.max(h[0][0],h[1][0])
                                &&h[0][1]>=Math.min(v[0][1],v[1][1])&&h[0][1]<=Math.max(v[0][1],v[1][1]))extra+=1800;
                        } else if(horizontal ? a[1]===s.a[1] : a[0]===s.a[0]) {
                            const axis=horizontal?0:1;
                            if(Math.min(Math.max(a[axis],b[axis]),Math.max(s.a[axis],s.b[axis]))
                                >Math.max(Math.min(a[axis],b[axis]),Math.min(s.a[axis],s.b[axis])))extra+=80;
                        }
                    }
                    const nk=next*3+nd,total=cost+extra;
                    if(total<(dist.get(nk)??Infinity)){dist.set(nk,total);parent.set(nk,key);heap.push([total,nk]);}
                }
            }
            if(found===undefined)throw new Error('No clear connector: '+edge.source+' → '+edge.target);
            const points=[];for(let k=found;k!==undefined;k=parent.get(k))points.push(point(Math.floor(k/3)));points.reverse();
            points.unshift([from.x+from.width,start[1]]);points.push([back?to.x+to.width:to.x,end[1]]);
            const compact=points.filter((p,i)=>!i||p[0]!==points[i-1][0]||p[1]!==points[i-1][1]);
            const simple=compact.filter((p,i)=>i===0||i===compact.length-1
                || !((p[0]===compact[i-1][0]&&p[0]===compact[i+1][0])||(p[1]===compact[i-1][1]&&p[1]===compact[i+1][1])));
            result[edge.index]=simple;
            for(let i=1;i<simple.length;i++)used.push({a:simple[i-1],b:simple[i],source:edge.source,target:edge.target});
        }
        return result;
    }
    scope.AiCallGraphRouter={route};
    if(typeof module!=='undefined')module.exports={route};
})(typeof window!=='undefined'?window:globalThis);
