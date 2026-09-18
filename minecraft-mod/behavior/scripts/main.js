// PhoneXR VR — VR for Minecraft Bedrock, in the spirit of Vivecraft.
//
// PhoneXR (the headset app) connects to the world with /connect and sends, 20 times a second,
//   /scriptevent phonexr:pose {"y":yaw,"p":pitch,"h":[[on,x,y,z,bits],[on,x,y,z,bits]]}
// yaw/pitch: the real head in degrees; h: left and right hand in metres relative to the head
// (x right, y up, z forward); bits: 1 fist, 2 "finger gun", 4 pinch.
//
// The head turns the player's view all the way round, the hands appear in the world, a fist breaks
// blocks and hits mobs, a pinch places the block in hand, and the finger gun walks forward.

import { world, system } from "@minecraft/server";

const FIST = 1;
const GUN = 2;
const PINCH = 4;
const WALK_PER_TICK = 0.2;
const REACH = 5;

let pose = null;
let poseTick = 0;
let tick = 0;
const players = new Map(); // player id -> state

system.afterEvents.scriptEventReceive.subscribe((event) => {
  if (event.id !== "phonexr:pose") return;
  try {
    pose = JSON.parse(event.message);
    poseTick = tick;
  } catch (e) {
    // A broken message is simply skipped.
  }
});

function valid(entity) {
  if (!entity) return false;
  try {
    return typeof entity.isValid === "function" ? entity.isValid() : entity.isValid;
  } catch (e) {
    return false;
  }
}

function stateOf(player) {
  let state = players.get(player.id);
  if (!state) {
    state = { yawOffset: null, hands: [null, null], bits: [0, 0], mineTick: [0, 0] };
    players.set(player.id, state);
  }
  return state;
}

function passable(dimension, location) {
  try {
    const block = dimension.getBlock(location);
    if (!block) return true;
    const id = block.typeId;
    return id === "minecraft:air" || id.endsWith("water") || id.includes("grass") && !id.includes("block") || id.includes("flower");
  } catch (e) {
    return false;
  }
}

function basis(rotation) {
  const yaw = (rotation.y * Math.PI) / 180;
  const pitch = (rotation.x * Math.PI) / 180;
  const forward = { x: -Math.sin(yaw) * Math.cos(pitch), y: -Math.sin(pitch), z: Math.cos(yaw) * Math.cos(pitch) };
  const right = { x: -Math.cos(yaw), y: 0, z: -Math.sin(yaw) };
  const up = {
    x: right.y * forward.z - right.z * forward.y,
    y: right.z * forward.x - right.x * forward.z,
    z: right.x * forward.y - right.y * forward.x,
  };
  return { forward, right, up };
}

function hit(player) {
  // A mob in reach first, otherwise the block the player looks at.
  try {
    const mobs = player.getEntitiesFromViewDirection({ maxDistance: 4 });
    const target = mobs.find((h) => h.entity && h.entity.typeId !== "phonexr:hand");
    if (target) {
      target.entity.applyDamage(4, { cause: "entityAttack", damagingEntity: player });
      return;
    }
  } catch (e) {}
  try {
    const ray = player.getBlockFromViewDirection({ maxDistance: REACH });
    if (!ray || !ray.block || ray.block.typeId === "minecraft:air" || ray.block.typeId === "minecraft:bedrock") return;
    const b = ray.block.location;
    player.dimension.runCommandAsync(`setblock ${b.x} ${b.y} ${b.z} air destroy`);
  } catch (e) {}
}

function place(player) {
  try {
    const ray = player.getBlockFromViewDirection({ maxDistance: REACH });
    if (!ray || !ray.block) return;
    const inventory = player.getComponent("minecraft:inventory");
    const slot = player.selectedSlotIndex ?? player.selectedSlot ?? 0;
    const item = inventory && inventory.container.getItem(slot);
    if (!item) return;
    const offsets = { Up: [0, 1, 0], Down: [0, -1, 0], North: [0, 0, -1], South: [0, 0, 1], East: [1, 0, 0], West: [-1, 0, 0] };
    const o = offsets[ray.face] || [0, 1, 0];
    const b = ray.block.location;
    const target = { x: b.x + o[0], y: b.y + o[1], z: b.z + o[2] };
    if (!passable(player.dimension, target)) return;
    player.dimension.getBlock(target).setType(item.typeId);
    let creative = false;
    try { creative = player.getGameMode && String(player.getGameMode()).toLowerCase() === "creative"; } catch (e) {}
    if (!creative) {
      if (item.amount > 1) { item.amount -= 1; inventory.container.setItem(slot, item); }
      else inventory.container.setItem(slot, undefined);
    }
  } catch (e) {
    // Not a block, or the block cannot go there.
  }
}

system.runInterval(() => {
  tick++;
  const fresh = pose && tick - poseTick < 20;
  for (const player of world.getAllPlayers()) {
    const state = stateOf(player);
    if (!fresh) {
      // PhoneXR went away: tidy the hands up.
      for (let i = 0; i < 2; i++) if (valid(state.hands[i])) { state.hands[i].remove(); state.hands[i] = null; }
      state.yawOffset = null;
      continue;
    }
    // Head → view. Minecraft's yaw grows to the right, the headset's to the left; pitch is flipped.
    if (state.yawOffset === null) state.yawOffset = player.getRotation().y + pose.y;
    const rotation = { x: Math.max(-89, Math.min(89, -pose.p)), y: state.yawOffset - pose.y };
    const { forward, right, up } = basis(rotation);

    // Walking with the finger gun, stepping up single blocks like auto-jump.
    let location = player.location;
    const walking = (pose.h || []).some((h) => h && h[0] && (h[4] & GUN));
    if (walking) {
      const flat = Math.hypot(forward.x, forward.z) || 1;
      const next = { x: location.x + (forward.x / flat) * WALK_PER_TICK, y: location.y, z: location.z + (forward.z / flat) * WALK_PER_TICK };
      const feet = { x: Math.floor(next.x), y: Math.floor(next.y), z: Math.floor(next.z) };
      const head = { x: feet.x, y: feet.y + 1, z: feet.z };
      if (passable(player.dimension, feet) && passable(player.dimension, head)) location = next;
      else if (passable(player.dimension, { x: feet.x, y: feet.y + 1, z: feet.z }) && passable(player.dimension, { x: feet.x, y: feet.y + 2, z: feet.z })) {
        location = { x: next.x, y: next.y + 1, z: next.z };
      }
    }
    try {
      player.teleport(location, { rotation, keepVelocity: true });
    } catch (e) {}

    // Hands in the world, and what they do.
    const eye = player.getHeadLocation();
    (pose.h || []).forEach((h, i) => {
      if (i > 1) return;
      if (!h || !h[0]) {
        if (valid(state.hands[i])) { state.hands[i].remove(); state.hands[i] = null; }
        state.bits[i] = 0;
        return;
      }
      const [, x, y, z, bits] = h;
      const at = {
        x: eye.x + right.x * x + up.x * y + forward.x * z,
        y: eye.y + right.y * x + up.y * y + forward.y * z,
        z: eye.z + right.z * x + up.z * y + forward.z * z,
      };
      try {
        if (!valid(state.hands[i])) state.hands[i] = player.dimension.spawnEntity("phonexr:hand", at);
        state.hands[i].teleport(at, { rotation: { x: rotation.x, y: rotation.y } });
      } catch (e) {}
      const before = state.bits[i];
      // A fist hits once, and keeps breaking while it stays closed.
      if (bits & FIST) {
        if (!(before & FIST) || tick - state.mineTick[i] >= 6) { hit(player); state.mineTick[i] = tick; }
      }
      if ((bits & PINCH) && !(before & PINCH)) place(player);
      state.bits[i] = bits;
    });
  }
}, 1);
