(function(root){
    'use strict';
    // Load first and retain the last good scene on network failure or out-of-order completion.
    function createSceneImageLoader(apply, makeImage){
        let request=0,current='';
        return function(url){
            if(typeof url!=='string'||!/^\/images\/(?:scenes|scenarios)\/[a-z0-9-]+\.(?:png|webp)$/.test(url))return;
            const ticket=++request;
            if(url===current)return;
            const image=makeImage();
            image.onload=function(){if(ticket===request){current=url;apply(url);}};
            image.onerror=function(){};
            image.src=url;
        };
    }
    if(typeof module==='object'&&module.exports)module.exports={createSceneImageLoader};
    else root.AiCallSceneImage={createSceneImageLoader};
})(typeof window==='object'?window:globalThis);
