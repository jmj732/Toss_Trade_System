// node:module load 훅. node --test 는 .jsx 를 해석하지 못하므로,
// JSX 를 기능 단위로 점진 도입하는 동안 esbuild 로 즉석 변환해 테스트에서 import 가능하게 한다.
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

import { transform } from "esbuild";

export async function load(url, context, nextLoad) {
  if (!url.endsWith(".jsx")) {
    return nextLoad(url, context);
  }
  const source = await readFile(fileURLToPath(url), "utf8");
  const { code } = await transform(source, {
    loader: "jsx",
    format: "esm",
    jsx: "automatic",
    target: "esnext"
  });
  return { format: "module", shortCircuit: true, source: code };
}
