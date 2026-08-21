export const ROOT_VOXYGEN_SANDBOX_IMAGE = "sandcastle:voxygen-monorepo";

export function mergerDockerOptions(env: Record<string, string>) {
  return {
    imageName: ROOT_VOXYGEN_SANDBOX_IMAGE,
    env,
  };
}
