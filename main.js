const prefersReducedMotion = window.matchMedia(
  "(prefers-reduced-motion: reduce)"
).matches;

const lerp = (start, end, factor) => start + (end - start) * factor;

function initParallax() {
  const parallaxTargets = document.querySelectorAll("[data-speed]");
  if (!parallaxTargets.length) return;

  let lastScrollY = window.scrollY;
  let currentScrollY = window.scrollY;
  const damping = 0.08;

  const update = () => {
    currentScrollY = lerp(currentScrollY, lastScrollY, 1 - damping);
    parallaxTargets.forEach((el) => {
      const speed = Number(el.dataset.speed) || 0;
      el.style.transform = `translateY(${currentScrollY * speed * -1}px)`;
    });
    requestAnimationFrame(update);
  };

  const onScroll = () => {
    lastScrollY = window.scrollY;
  };

  window.addEventListener("scroll", onScroll, { passive: true });
  update();
}

function initTilt() {
  const tiltTarget = document.querySelector("[data-tilt]");
  if (!tiltTarget) return;

  const maxTilt = 8;
  const resetTilt = () => {
    tiltTarget.style.transform = "rotateX(0deg) rotateY(0deg)";
  };

  tiltTarget.addEventListener("pointermove", (event) => {
    const rect = tiltTarget.getBoundingClientRect();
    const x = ((event.clientX - rect.left) / rect.width - 0.5) * 2;
    const y = ((event.clientY - rect.top) / rect.height - 0.5) * 2;
    const rotateX = y * -maxTilt;
    const rotateY = x * maxTilt;
    tiltTarget.style.transform = `rotateX(${rotateX}deg) rotateY(${rotateY}deg)`;
  });

  tiltTarget.addEventListener("pointerleave", resetTilt);
  tiltTarget.addEventListener("pointerup", resetTilt);
  tiltTarget.addEventListener("pointercancel", resetTilt);
}

function initGSAP() {
  if (prefersReducedMotion || typeof gsap === "undefined") return;
  if (typeof ScrollTrigger !== "undefined") {
    gsap.registerPlugin(ScrollTrigger);
  }

  gsap.from(".hero__copy", {
    opacity: 0,
    y: 40,
    duration: 1.2,
    ease: "power3.out",
  });

  gsap.from(".hero__device", {
    opacity: 0,
    y: 80,
    duration: 1.4,
    ease: "power3.out",
    delay: 0.2,
  });

  document.querySelectorAll(".reveal").forEach((section) => {
    gsap.from(section, {
      opacity: 0,
      y: 80,
      duration: 1,
      ease: "power2.out",
      scrollTrigger: {
        trigger: section,
        start: "top 78%",
        once: true,
      },
    });
  });
}

window.addEventListener("DOMContentLoaded", () => {
  initParallax();
  initTilt();
  initGSAP();
});
