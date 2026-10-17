(function () {
    'use strict';
    const mobile = window.matchMedia('(max-width: 900px)');
    const viewer = document.getElementById('large-subtitle-viewer');
    const transcript = document.querySelector('.transcript-column');
    if (!viewer || !transcript) return;
    const anchor = document.createComment('desktop transcript position');
    transcript.before(anchor);
    function adapt() {
        if (mobile.matches) viewer.append(transcript);
        else {
            anchor.after(transcript);
            window.shadowingListenRecord?.setEnabled(false);
        }
    }
    mobile.addEventListener('change', adapt);
    adapt();
}());
