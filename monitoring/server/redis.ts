import { createClient } from "redis";

const client = createClient({ url: "redis://localhost:6379" });

await client.connect();

export const get = (key) => client.get(key);
export const set = (key, value) => client.set(key, value);
export const del = (key) => client.del(key);
export const exists = (key) => client.exists(key);
