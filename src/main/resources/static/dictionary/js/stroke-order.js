/**
 * Trình phát hoạt ảnh thứ tự nét cho file KanjiVG (kanji.stroke_svg_uri, dữ liệu đã import
 * ở JLAS2-19). Không thêm nguồn dữ liệu mới: chỉ animate lại đúng file SVG tĩnh đã có, dựa trên
 * cấu trúc chuẩn KanjiVG — mỗi nét là một <path id="...-sN"> theo đúng thứ tự viết.
 */
(function (global) {
  'use strict';

  var STROKE_MS = 420;
  var STAGGER_MS = 260;

  function strokeIndex(path) {
    var match = /-s(\d+)$/.exec(path.id || '');
    return match ? parseInt(match[1], 10) : 0;
  }

  async function fetchSvgElement(url) {
    var response = await fetch(url);
    if (!response.ok) throw new Error('stroke svg fetch failed');
    var text = await response.text();
    var doc = new DOMParser().parseFromString(text, 'image/svg+xml');
    var svg = doc.querySelector('svg');
    if (!svg) throw new Error('invalid stroke svg');
    return svg;
  }

  function prepare(svg) {
    svg.classList.add('dict-stroke-order__svg');
    svg.removeAttribute('width');
    svg.removeAttribute('height');

    var paths = Array.prototype.slice.call(svg.querySelectorAll('path[id*="-s"]'))
      .sort(function (a, b) { return strokeIndex(a) - strokeIndex(b); });
    var numbers = Array.prototype.slice.call(svg.querySelectorAll('g[id*="StrokeNumbers"] text'));

    paths.forEach(function (path) {
      var length = path.getTotalLength();
      path.style.strokeDasharray = String(length);
      path.style.strokeDashoffset = String(length);
      path.style.transition = 'none';
    });
    numbers.forEach(function (text) { text.style.opacity = '0'; text.style.transition = 'opacity 180ms ease'; });

    return { paths: paths, numbers: numbers };
  }

  function play(strokes) {
    strokes.paths.forEach(function (path, index) {
      window.setTimeout(function () {
        path.style.transition = 'stroke-dashoffset ' + STROKE_MS + 'ms ease';
        path.style.strokeDashoffset = '0';
        var number = strokes.numbers[index];
        if (number) number.style.opacity = '1';
      }, index * STAGGER_MS);
    });
  }

  function reset(strokes) {
    strokes.paths.forEach(function (path) {
      path.style.transition = 'none';
      path.style.strokeDashoffset = path.style.strokeDasharray;
    });
    strokes.numbers.forEach(function (text) { text.style.opacity = '0'; });
    // Force reflow so the next play() transition is picked up instead of being coalesced.
    void strokes.paths[0] && strokes.paths[0].getBoundingClientRect();
  }

  /**
   * Render + tự động phát hoạt ảnh thứ tự nét vào `container`.
   * Trả về hàm replay(), hoặc null nếu tải/parse SVG thất bại (container sẽ để trống,
   * gọi nơi dùng tự quyết định fallback).
   */
  async function mount(container, url) {
    var svg;
    try {
      svg = await fetchSvgElement(url);
    } catch (error) {
      return null;
    }
    container.innerHTML = '';
    container.appendChild(svg);
    var strokes = prepare(svg);
    play(strokes);
    return function replay() {
      reset(strokes);
      window.requestAnimationFrame(function () { play(strokes); });
    };
  }

  global.DictStrokeOrder = { mount: mount };
})(window);
