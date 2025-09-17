// @ts-check
const canvas = document.querySelector('canvas#previewCanvas'); // IDを指定して取得を推奨
const ctx = canvas.getContext('2d');
const textInput = document.querySelector('textarea#textInput');
const defaultText = textInput.value;
let inverted = false;
let bgColor = 'white';
let textColor = 'black';

let currentFontFace = 'Arial'; // 現在選択されているフォントのCSS Family名を保持 (初期値Arial)

const commonInstance = new NfcEIWCommon(canvas); // NfcEIWCommon が定義されていると仮定
const clearCanvas = commonInstance.clearCanvas.bind(commonInstance);

const clearTextButton = document.querySelector('button#clearTextButton');

/**
 * Androidから呼び出される関数。WebViewにフォントを適用する。
 * @param {string} fontCssFamily CSSのfont-family名
 */
// @ts-ignore
function setFontFamily(fontCssFamily) {
    if (typeof fontCssFamily === 'string' && fontCssFamily.trim() !== '') {
        currentFontFace = fontCssFamily;
        console.log(`Font set to by Android: ${currentFontFace}`);
        renderToCanvas();
    } else {
        console.warn(`Invalid font family received from Android: ${fontCssFamily}, using current: ${currentFontFace}`);
    }
}

/**
 * テキストをCanvasの中央にフィットさせて描画
 * @param {string} textToDraw 表示するテキスト
 * @param {number} [paddingW=4] 左右のパディング
 */
function fitAndFillTextCenter(textToDraw, paddingW = 4) {
    if (!textToDraw) {
        clearCanvas();
        drawBg();
        return;
    }
    const { height: canvasH, width: canvasW } = canvas;
    let fontSize = getFontSizeToFit(textToDraw, currentFontFace, canvasW - (paddingW * 2), canvasH);
    ctx.fillStyle = textColor;
    ctx.font = fontSize + `px ${currentFontFace}`;

    ctx.textBaseline = 'middle';
    ctx.textAlign = 'center';

    const x = 0;
    const y = 0;
    const lines = textToDraw.match(/[^\r\n]+/g);

    if (lines && lines.length > 0) {
        for (let i = 0; i < lines.length; i++) {
            const lineText = lines[i];
            let xL = (canvasW - x) / 2;
            let yL = y + (canvasH / (lines.length + 1)) * (i + 1);
            ctx.fillText(lineText, xL, yL);
        }
    }
}

/**
 * 指定された幅と高さにテキストを収めるためのフォントサイズを取得
 * @param {string} textToMeasure サイズ計測対象のテキスト
 * @param {string} fontFaceToUse CSSのfont-family名
 * @param {number} targetWidth 利用可能な幅
 * @param {number} targetHeight 利用可能な高さ
 * @returns {number} 計算されたフォントサイズ(px単位)
 */
function getFontSizeToFit(textToMeasure, fontFaceToUse, targetWidth, targetHeight) {
    const lineSpacingPercent = 20;
    ctx.font = `1px ${fontFaceToUse}`;

    let maxLineWidth = 0;
    let lineCount = 1;
    const lines = textToMeasure.match(/[^\r\n]+/g);

    if (lines && lines.length > 0) {
        lineCount = lines.length;
        lines.forEach((line) => {
            const metrics = ctx.measureText(line);
            maxLineWidth = Math.max(maxLineWidth, metrics.width);
        });
    } else {
        maxLineWidth = 0;
    }

    let fontSizeBasedOnWidth;
    if (maxLineWidth === 0 || targetWidth === 0) {
        fontSizeBasedOnWidth = Number.MAX_VALUE;
    } else {
        fontSizeBasedOnWidth = targetWidth / maxLineWidth;
    }

    let fontSizeBasedOnHeight = targetHeight / (lineCount * (1 + (lineSpacingPercent / 100)));
    return Math.min(fontSizeBasedOnHeight, fontSizeBasedOnWidth);
}

/**
 * Normal operation is black text on white, but you can set inverted
 * @param {boolean} [updatedInverted]
 */
function setInverted(updatedInverted) {
    inverted = typeof updatedInverted === 'boolean' ? updatedInverted : !inverted;
    if (inverted) {
        bgColor = 'black';
        textColor = 'white';
    } else {
        bgColor = 'white';
        textColor = 'black';
    }
    renderToCanvas();
}

function drawBg() {
    ctx.fillStyle = bgColor;
    ctx.fillRect(0, 0, canvas.width, canvas.height);
}

function renderToCanvas() {
    clearCanvas();
    drawBg();
    fitAndFillTextCenter(textInput.value);
}

renderToCanvas();

textInput.addEventListener('keyup', renderToCanvas);

document.querySelector('button#addLineBreak')?.addEventListener('click', () => {
    textInput.value += '\n';
    renderToCanvas();
});
document.querySelector('button#reset')?.addEventListener('click', () => {
    textInput.value = defaultText;
    setInverted(false);
    setFontFamily('Arial');
});
document.querySelector('button#setInverted')?.addEventListener('click', () => {
    setInverted();
});

if (clearTextButton) {
    clearTextButton.addEventListener('click', () => {
        textInput.value = '';
        renderToCanvas();
    });
} else {
    console.warn('Clear text button (button#clearTextButton) not found.');
}
