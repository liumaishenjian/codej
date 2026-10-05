/** 项目已有启动字形与配色；新旧界面共享，避免品牌样式漂移。 */
export const CODEJ_BANNER = [
  ' ██████  ██████  ██████  ███████     ██',
  '██      ██    ██ ██   ██ ██          ██',
  '██      ██    ██ ██   ██ █████       ██',
  '██      ██    ██ ██   ██ ██      ██  ██',
  ' ██████  ██████  ██████  ███████  ████',
] as const;
export const codejBannerColor = (index: number): string => index < 2 ? 'cyanBright' : index < 4 ? 'blueBright' : 'magentaBright';
