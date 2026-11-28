package org.openleaf.reader;

/** Trusted reader code, evaluated by the host after sanitized book content has loaded. */
final class EpubPageLayout {
    private EpubPageLayout() { }

    static String css(boolean paged) {
        String shared = "html{width:100%;}body{overflow-wrap:anywhere;}"
                + "#epub-content{min-width:0;}img{object-fit:contain;}"
                + "#epub-content p,#epub-content li{orphans:2;widows:2;}";
        if (!paged) return shared
                + "html{overflow-x:hidden;overflow-y:auto;}body{box-sizing:border-box;max-width:42em;margin:0 auto;padding:20px 20px 40px;}"
                + "#epub-content{padding-bottom:1em;}";
        return shared
                // overflow:hidden would make the document non-scrollable here, so scrollTo() clamps
                // to 0 and pages can only be reached by dragging. Keep horizontal scrolling real.
                + "html{height:100%;overflow-x:auto;overflow-y:hidden;}body{box-sizing:border-box;width:100%;height:100%;margin:0;padding:16px 20px;max-width:none;}"
                + "#epub-content{height:100%;column-width:calc(100vw - 40px);column-gap:40px;column-fill:auto;}"
                + "#epub-content img{max-height:calc(100vh - 8em);width:auto;}"
                + "#epub-content figure{break-inside:avoid;max-width:100%;}"
                + "#epub-content table{overflow:visible;}"
                + "#epub-content pre{white-space:pre-wrap;}";
    }

    static String bootstrap(boolean paged) {
        return "(function(){var paged=" + paged + ";" + SCRIPT + "})()";
    }

    private static final String SCRIPT = """
        var content=document.getElementById('epub-content');
        if(!content)return [0,1];
        function viewW(){return Math.max(1,document.documentElement.clientWidth||document.body.clientWidth||1);}
        function viewH(){return Math.max(1,document.documentElement.clientHeight||document.body.clientHeight||1);}
        function width(){return viewW();}
        function count(){return paged?Math.max(1,Math.ceil((content.scrollWidth+40-1)/width())):1;}
        function index(){return paged?Math.max(0,Math.min(count()-1,Math.round(window.scrollX/width()))):0;}
        function go(n){n=Math.max(0,Math.min(count()-1,n));var x=paged?n*width():0;window.scrollTo(x,0);
          if(paged&&Math.abs(window.scrollX-x)>1){content.scrollWidth;window.scrollTo(x,0);}return [index(),count()];}
        if(paged){
          var lh=parseFloat(getComputedStyle(content).lineHeight)||32;
          // viewH() is the layout viewport; innerHeight can include a scaled visual viewport.
          content.style.height=Math.max(lh,Math.floor((viewH()-32)/lh)*lh)+'px';
        }
        function visible(r){return r.width>0&&r.height>0&&r.right>20&&r.left<width()-20&&r.bottom>16&&r.top<viewH()-16;}
        function onScreen(el){
          var rects=el.getClientRects();
          for(var i=0;i<rects.length;i++){if(visible(rects[i]))return true;}
          return false;
        }
        function capture(){
          var node=null,off=0;
          if(document.caretRangeFromPoint){
            var range=document.caretRangeFromPoint(22,18+(parseFloat(getComputedStyle(content).lineHeight)||32)/2);
            if(range&&content.contains(range.startContainer)){node=range.startContainer;off=range.startOffset;}
          }
          var element=node?(node.nodeType===1?node:node.parentElement):null;
          while(element&&element!==content&&!element.hasAttribute('data-cfi'))element=element.parentElement;
          // A caret that resolves to an off-screen wrapper (multi-column paint offset) must not win.
          if(element&&element!==content&&onScreen(element)){
            var before=document.createRange();before.selectNodeContents(element);
            try{before.setEnd(node,off);return {path:element.getAttribute('data-cfi'),offset:before.toString().length};}catch(ignored){}
          }
          var all=content.querySelectorAll('[data-cfi]'),best=null,bestY=1e9,bestX=1e9,bestDepth=-1;
          for(var i=0;i<all.length;i++){
            var depth=0,up=all[i].parentElement;
            while(up&&up!==content){depth++;up=up.parentElement;}
            var rects=all[i].getClientRects();
            for(var j=0;j<rects.length;j++){
              var r=rects[j];
              if(visible(r)&&(r.top<bestY-1||(Math.abs(r.top-bestY)<1&&(depth>bestDepth||(depth===bestDepth&&r.left<bestX))))){best=all[i];bestY=r.top;bestX=r.left;bestDepth=depth;}
            }
          }
          return best?{path:best.getAttribute('data-cfi'),offset:0}:null;
        }
        function restore(path,offset,fallback,end){
          if(end){return go(count()-1);}
          var e=path?content.querySelector('[data-cfi="'+path+'"]'):null;
          if(e){
            var walker=document.createTreeWalker(e,NodeFilter.SHOW_TEXT),node=null,remaining=Math.max(0,offset),r=null,last=null;
            while(node=walker.nextNode()){
              if(remaining<node.length){var range=document.createRange();range.setStart(node,remaining);range.setEnd(node,Math.min(node.length,remaining+1));r=range.getBoundingClientRect();break;}
              remaining-=node.length;
              if(node.length){var tail=document.createRange();tail.setStart(node,node.length-1);tail.setEnd(node,node.length);last=tail.getBoundingClientRect();}
            }
            if((!r||(!r.width&&!r.height))&&last&&(last.width||last.height))r=last;
            if(!r||(!r.width&&!r.height)){var rects=e.getClientRects();r=rects.length?rects[0]:null;}
            if(r){if(paged)return go(Math.floor((r.left+window.scrollX)/width()));window.scrollTo(0,Math.max(0,r.top+window.scrollY-16));return [0,1];}
          }
          if(paged)return go(fallback);
          window.scrollTo(0,Math.max(0,fallback));return [0,1];
        }
        window.__openleafReader={capture:capture,restore:restore,go:go,index:index,count:count};
        return [index(),count()];
        """;
}
