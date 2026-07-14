export function removeFileFromPreview(files, preview, relativePath) {
  return {
    files: files.filter((item) => item.relativePath !== relativePath),
    preview: preview.filter((item) => item.relativePath !== relativePath),
  };
}
