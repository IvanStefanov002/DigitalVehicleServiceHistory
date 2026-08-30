(function () {
    if (window.__mvBound) return;
    window.__mvBound = true;

    var VAL = __MV_VALUE__;
    var SELS = __MV_SELECTORS__;

    function setVal(el, val) {
        try {
            var d = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value');
            if (d && d.set) {
                d.set.call(el, val);
            } else {
                el.value = val;
            }
            ['input', 'change', 'keyup', 'blur'].forEach(function (t) {
                el.dispatchEvent(new Event(t, { bubbles: true }));
            });
        } catch (e) { /* a page we can't drive is a page the user fills in by hand */ }
    }

    function findField() {
        for (var i = 0; i < SELS.length; i++) {
            var el = document.querySelector(SELS[i]);
            if (el) return el;
        }
        var ins = document.querySelectorAll('input[type="text"],input:not([type])');
        for (var j = 0; j < ins.length; j++) {
            if (ins[j].offsetParent !== null) return ins[j];
        }
        return null;
    }

    var n = 0;
    var timer = setInterval(function () {
        n++;
        var el = findField();
        if (el && VAL) {
            setVal(el, VAL);
            clearInterval(timer);
        }
        if (n > 40) clearInterval(timer);
    }, 250);

    var DATE_RE = /\b\d{2}\.\d{2}\.\d{4}\b/;
    var last = '';

    function scan() {
        try {
            var els = document.querySelectorAll('td,th,li,p,span,div,strong,b,label');
            var out = [], seen = {};
            for (var i = 0; i < els.length; i++) {
                var tx = (els[i].textContent || '').trim();
                if (!tx || tx.length > 120) continue;
                var m = tx.match(DATE_RE);
                if (!m) continue;
                var lab = tx.replace(m[0], '').replace(/[\s:\-–]+$/, '').trim().slice(0, 60);
                var k = m[0] + '|' + lab;
                if (seen[k]) continue;
                seen[k] = 1;
                out.push({ date: m[0], label: lab });
            }
            if (out.length) {
                var pl = JSON.stringify(out);
                if (pl !== last) {
                    last = pl;
                    if (window.MvBridge && MvBridge.onDatesFound) MvBridge.onDatesFound(pl);
                }
            }
        } catch (e) { /* see the header: finding nothing is an acceptable outcome */ }
    }

    try {
        new MutationObserver(scan).observe(document.body, {
            childList: true, subtree: true, characterData: true
        });
    } catch (e) { /* no observer: the initial scan below is then all we get */ }

    scan();
})();
