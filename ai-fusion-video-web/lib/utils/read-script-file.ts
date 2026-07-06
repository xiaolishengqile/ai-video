/** 剧本文件上传支持的格式 */
export const SCRIPT_FILE_ACCEPT = ".txt,.text,.md,.markdown,.docx,.fountain,.fdx";

const TEXT_EXTENSIONS = [".txt", ".text", ".md", ".markdown", ".fountain", ".fdx"];

function getExtension(fileName: string): string {
  const index = fileName.lastIndexOf(".");
  return index >= 0 ? fileName.slice(index).toLowerCase() : "";
}

/**
 * 从本地剧本文件读取纯文本内容。
 * 支持 .txt / .md / .fountain 等文本格式，以及 .docx。
 */
export async function readScriptFile(file: File): Promise<string> {
  const extension = getExtension(file.name);

  if (extension === ".docx") {
    const mammoth = await import("mammoth");
    const arrayBuffer = await file.arrayBuffer();
    const result = await mammoth.extractRawText({ arrayBuffer });
    return result.value.replace(/\r\n/g, "\n").trim();
  }

  if (TEXT_EXTENSIONS.includes(extension) || file.type.startsWith("text/")) {
    const text = await file.text();
    return text.replace(/\r\n/g, "\n").trim();
  }

  throw new Error(`不支持的文件格式「${extension || file.type}」，请上传 .txt 或 .docx 文件`);
}
