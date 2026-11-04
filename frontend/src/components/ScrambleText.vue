<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  text: string
  delay?: number
}>(), {
  delay: 0,
})
const root = ref<HTMLElement | null>(null)
const displayed = ref(props.text)
const symbols = ['#', '/', '+', '=', '*', '?', '0', '1']
let timer: ReturnType<typeof setTimeout> | undefined
let observer: IntersectionObserver | undefined
let motion: MediaQueryList | undefined
let played = false

function stop() {
  clearTimeout(timer)
  timer = undefined
  displayed.value = props.text
}

function play() {
  stop()
  played = true
  observer?.disconnect()
  if (motion?.matches) return
  const characters = Array.from(props.text)
  let frame = 0
  function tick() {
    const revealed = Math.floor(frame / 2)
    displayed.value = characters
      .map((character, index) =>
        index < revealed || /\s/.test(character)
          ? character
          : symbols[(frame + index * 3) % symbols.length],
      )
      .join('')
    frame++
    if (revealed < characters.length) timer = setTimeout(tick, 42)
    else stop()
  }
  timer = setTimeout(tick, props.delay)
}

function onMotionChange() {
  if (motion?.matches) stop()
}

onMounted(() => {
  motion = window.matchMedia?.('(prefers-reduced-motion: reduce)')
  motion?.addEventListener('change', onMotionChange)
  if (motion?.matches || !window.IntersectionObserver) return
  observer = new IntersectionObserver(
    (entries) => {
      if (!played && entries.some((entry) => entry.isIntersecting)) play()
    },
    { threshold: 0.5 },
  )
  if (root.value) observer.observe(root.value)
})

watch(() => props.text, () => {
  stop()
})

onBeforeUnmount(() => {
  stop()
  observer?.disconnect()
  motion?.removeEventListener('change', onMotionChange)
})

</script>

<template>
  <span ref="root" class="scramble-text">
    <span class="sr-only">{{ text }}</span>
    <span class="scramble-visual" aria-hidden="true">
      <span class="scramble-measure">{{ text }}</span>
      <span class="scramble-output">{{ displayed }}</span>
    </span>
  </span>
</template>

<style scoped>
.scramble-visual { display: inline-grid; }
.scramble-measure, .scramble-output { grid-area: 1 / 1; }
.scramble-measure { visibility: hidden; }
.scramble-output { white-space: nowrap; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; border: 0; }
</style>
