/* Bot Tipper — ambient light, reveals, and the specular highlight on the glass.
   Everything here is decoration. If it never runs, the page still reads: the CSS only hides
   content once this script has declared which reveal strategy is in play. */
(() => {
  'use strict';

  const root = document.documentElement;
  const still = matchMedia('(prefers-reduced-motion: reduce)');

  /* ── reveal strategy ──────────────────────────────────────────────────
     Scroll-driven CSS animations where the browser has them, IntersectionObserver
     everywhere else. Deciding once, here, keeps the two from overlapping. */
  const hasViewTimeline =
    typeof CSS !== 'undefined' &&
    CSS.supports &&
    CSS.supports('animation-timeline: view()');

  root.classList.add(hasViewTimeline ? 'has-vt' : 'no-vt');

  if (!hasViewTimeline) {
    const targets = document.querySelectorAll(
      '.reveal, .scene__text, .keep__head, .step, .card, .chat, .close > *'
    );
    if ('IntersectionObserver' in window && !still.matches) {
      const io = new IntersectionObserver((entries) => {
        for (const e of entries) {
          if (!e.isIntersecting) continue;
          e.target.classList.add('is-in');
          io.unobserve(e.target);
        }
      }, { rootMargin: '0px 0px -12% 0px', threshold: 0.05 });
      targets.forEach((el) => io.observe(el));
    } else {
      // No observer, or the visitor asked for stillness: show everything at once.
      targets.forEach((el) => el.classList.add('is-in'));
    }
  }

  /* ── specular highlight ───────────────────────────────────────────────
     The pane's ::after gradient is positioned from these two properties, so the light
     appears to track the pointer across the glass. Coarse pointers never hover, so
     there is nothing to bind there. */
  if (matchMedia('(hover: hover) and (pointer: fine)').matches) {
    for (const pane of document.querySelectorAll('[data-lit]')) {
      pane.addEventListener('pointermove', (e) => {
        const r = pane.getBoundingClientRect();
        pane.style.setProperty('--mx', `${((e.clientX - r.left) / r.width) * 100}%`);
        pane.style.setProperty('--my', `${((e.clientY - r.top) / r.height) * 100}%`);
      });
    }
  }

  /* ── caustics ─────────────────────────────────────────────────────────
     Light through moving water, which is the same thing light through a turning crystal
     looks like. Drawn at a twelfth of the window and scaled up by the CSS blur — the
     softness is the effect, so paying for real pixels would buy nothing. */
  const canvas = document.getElementById('caustics');
  if (!canvas) return;
  const ctx = canvas.getContext('2d', { alpha: true });
  if (!ctx) return;

  const SCALE = 12;
  const LIGHTS = [
    { hue: '52, 183, 255', r: 0.55, ax: 0.30, ay: 0.20, sx: 0.00007, sy: 0.00011, px: 0, py: 1.1 },
    { hue: '139, 107, 255', r: 0.50, ax: 0.26, ay: 0.24, sx: 0.00009, sy: 0.00006, px: 2.1, py: 0.4 },
    { hue: '255, 201, 120', r: 0.30, ax: 0.34, ay: 0.18, sx: 0.00005, sy: 0.00013, px: 4.2, py: 2.7 },
  ];

  let w = 0;
  let h = 0;

  function resize() {
    w = canvas.width = Math.max(1, Math.ceil(innerWidth / SCALE));
    h = canvas.height = Math.max(1, Math.ceil(innerHeight / SCALE));
  }

  function draw(t) {
    ctx.clearRect(0, 0, w, h);
    ctx.globalCompositeOperation = 'lighter';
    for (const L of LIGHTS) {
      const x = (0.5 + Math.sin(t * L.sx + L.px) * L.ax) * w;
      const y = (0.5 + Math.cos(t * L.sy + L.py) * L.ay) * h;
      const rad = L.r * Math.max(w, h);
      const g = ctx.createRadialGradient(x, y, 0, x, y, rad);
      g.addColorStop(0, `rgba(${L.hue}, 0.55)`);
      g.addColorStop(0.5, `rgba(${L.hue}, 0.14)`);
      g.addColorStop(1, `rgba(${L.hue}, 0)`);
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.arc(x, y, rad, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.globalCompositeOperation = 'source-over';
  }

  resize();
  addEventListener('resize', () => { resize(); draw(performance.now()); }, { passive: true });

  if (still.matches) {
    draw(0);                       // one frame, held still
  } else {
    // ~30fps is indistinguishable from 60 behind a 38px blur, and costs half as much.
    let last = 0;
    const loop = (now) => {
      if (now - last > 33) { draw(now); last = now; }
      requestAnimationFrame(loop);
    };
    requestAnimationFrame(loop);
  }
})();
