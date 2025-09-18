// @ts-check

// DOM要素の取得は DOMContentLoaded 後に行うのがより安全ですが、
// 以前のコードがグローバルスコープで取得していたため、それに倣いつつ、
// 存在確認を追加したり、初期化処理を工夫しています。

let canvas = null;
let ctx = null;
let textInput = null;
let defaultText = ''; // 初期値
let clearTextButton = null;

let inverted = false;
let bgColor = 'white';
let textColor = 'black';
let currentFontFace = 'Arial'; // 現在選択されているフォントのCSS Family名を保持

// NfcEIWCommon と clearCanvas の扱い
// 元のコードにあった NfcEIWCommon のインスタンス化と clearCanvas の取得
// これらが別の場所で定義・ロードされていることを期待します。
// もし未定義の場合、clearCanvasSafe でフォールバック処理をします。
let commonInstance = null;
let clearCanvas = null;


/**
 * Androidから呼び出される関数。WebViewにフォントを適用する。
 * @param {string} fontCssFamily CSSのfont-family名
 */
// @ts-ignore
async function setFontFamily(fontCssFamily) {
    if (typeof fontCssFamily === 'string' && fontCssFamily.trim() !== '') {
        const newFontFace = fontCssFamily;
        console.log(`setFontFamily called with: ${newFontFace}`);

        if (!document.fonts) {
            console.warn("document.fonts API not supported. Font loading might not work as expected.");
            currentFontFace = newFontFace;
            renderToCanvas();
            return;
        }

        try {
            await document.fonts.load(`10px "${newFontFace}"`); // フォント名にスペースが含まれる可能性を考慮し引用符追加
            console.log(`Font "${newFontFace}" loaded or already available.`);
            currentFontFace = newFontFace;
        } catch (error) {
            console.warn(`Failed to load font "${newFontFace}":`, error);
            // フォントのロードに失敗した場合、デフォルトに戻すなどの処理も検討可
            // currentFontFace = 'Arial'; // 例: デフォルトフォントに戻す
        } finally {
            renderToCanvas(); // 成功・失敗に関わらず再描画
        }
    } else {
        console.warn(`Invalid font family received from Android: ${fontCssFamily}, using current: ${currentFontFace}`);
        // 不正なフォントファミリーでも、現在のフォントで再描画を試みる
        renderToCanvas();
    }
}

/**
 * テキストをCanvasの中央にフィットさせて描画
 * @param {string} textToDraw 表示するテキスト
 * @param {number} [paddingW=4] 左右のパディング
 */
function fitAndFillTextCenter(textToDraw, paddingW = 4) {
    if (!canvas || !ctx) {
        console.error("Canvas or context not available for fitAndFillTextCenter.");
        return;
    }
    if (!textToDraw) {
        clearCanvasSafe();
        drawBg();
        return;
    }

    const { height: canvasH, width: canvasW } = canvas;
    let fontSize = getFontSizeToFit(textToDraw, currentFontFace, canvasW - (paddingW * 2), canvasH);

    ctx.fillStyle = textColor;
    ctx.font = `${fontSize}px "${currentFontFace}"`; // フォント名にスペースが含まれる可能性を考慮し引用符追加
    ctx.textBaseline = 'middle';
    ctx.textAlign = 'center';

    const lines = textToDraw.match(/[^\r\n]+/g) || [""]; // テキストが空でも空行1つとして扱う

    for (let i = 0; i < lines.length; i++) {
        const lineText = lines[i];
        let xL = canvasW / 2;
        let yL = (canvasH / (lines.length + 1)) * (i + 1);
        ctx.fillText(lineText, xL, yL);
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
    if (!ctx) {
        console.error("Context not available for getFontSizeToFit");
        return 10; // デフォルトサイズ
    }
    const lineSpacingPercent = 20;
    ctx.font = `1px "${fontFaceToUse}"`; // フォント名にスペースが含まれる可能性を考慮し引用符追加

    let maxLineWidth = 0;
    const lines = textToMeasure.match(/[^\r\n]+/g) || [""];
    const lineCount = lines.length;

    lines.forEach((line) => {
        const metrics = ctx.measureText(line);
        maxLineWidth = Math.max(maxLineWidth, metrics.width);
    });

    let fontSizeBasedOnWidth;
    if (maxLineWidth === 0 || targetWidth <= 0) {
        fontSizeBasedOnWidth = targetHeight; // 幅が0なら高さ基準のフォントサイズを使うか、大きな値
    } else {
        fontSizeBasedOnWidth = targetWidth / maxLineWidth;
    }

    let fontSizeBasedOnHeight;
    if (targetHeight <= 0 || lineCount <= 0) {
        fontSizeBasedOnHeight = Number.MAX_VALUE;
    } else {
        fontSizeBasedOnHeight = targetHeight / (lineCount * (1 + (lineSpacingPercent / 100)));
    }

    const calculatedSize = Math.min(fontSizeBasedOnHeight, fontSizeBasedOnWidth);
    return Math.max(1, calculatedSize); // 最小フォントサイズを1pxとする
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
    if (!canvas || !ctx) return;
    ctx.fillStyle = bgColor;
    ctx.fillRect(0, 0, canvas.width, canvas.height);
}

function clearCanvasSafe() {
    if (typeof clearCanvas === 'function') {
        try {
            clearCanvas();
        } catch (e) {
            console.error("Error calling external clearCanvas:", e);
            if (canvas && ctx) ctx.clearRect(0, 0, canvas.width, canvas.height); // フォールバック
        }
    } else if (canvas && ctx) {
        ctx.clearRect(0, 0, canvas.width, canvas.height);
    } else {
        // console.error("Canvas or context not available for clearCanvasSafe");
    }
}

function renderToCanvas() {
    if (!canvas || !ctx) {
        // console.warn("renderToCanvas called before canvas/ctx initialized.");
        return;
    }
    clearCanvasSafe();
    drawBg();
    if (textInput) {
        fitAndFillTextCenter(textInput.value);
    } else {
        fitAndFillTextCenter(""); // textInputがない場合は空文字で描画
    }
}

// Androidから呼び出される、画像データをBase64で取得する関数
function getImgSerializedFromCanvas(type, quality, callback) {
    if (!canvas) {
        console.error("getImgSerializedFromCanvas: Canvas not available.");
        if (callback && typeof callback === 'function') {
            callback(null); // エラー時はnullをコールバック
        }
        return;
    }
    try {
        const dataURL = canvas.toDataURL(type || 'image/png', quality || 0.92); // qualityはオプション
        const base64 = dataURL.split(',')[1];
        if (callback && typeof callback === 'function') {
            callback(base64);
        } else {
            console.warn("getImgSerializedFromCanvas: callback is not a function or not provided.");
        }
    } catch (e) {
        console.error("Error in getImgSerializedFromCanvas:", e);
        if (callback && typeof callback === 'function') {
            callback(null); // エラー時はnullをコールバック
        }
    }
}


// --- 初期化処理 ---
function initializeEditor() {
    canvas = document.querySelector('canvas#previewCanvas');
    if (canvas) {
        ctx = canvas.getContext('2d');
    } else {
        console.error("Canvas element #previewCanvas not found!");
        return; // canvasがないと致命的
    }

    textInput = document.querySelector('textarea#textInput');
    if (textInput) {
        defaultText = textInput.value; // 初期値を保持
        textInput.addEventListener('input', renderToCanvas); // keyupからinputに変更してペーストなどにも対応
    } else {
        console.warn("Text input element #textInput not found.");
    }

    // NfcEIWCommon のインスタンス化 (もしあれば)
    if (typeof NfcEIWCommon === 'function') {
        try {
            commonInstance = new NfcEIWCommon(canvas);
            if (commonInstance && typeof commonInstance.clearCanvas === 'function') {
                clearCanvas = commonInstance.clearCanvas.bind(commonInstance);
            } else {
                 console.warn("NfcEIWCommon.clearCanvas method not found or not a function.");
            }
        } catch(e) {
            console.error("Error instantiating NfcEIWCommon:", e);
        }
    } else {
        console.info("NfcEIWCommon class not found. Using default canvas clearing.");
    }


    document.querySelector('button#addLineBreak')?.addEventListener('click', () => {
        if (textInput) {
            // textInput.value += '\n'; // inputイベントで再描画される
            // textInput.focus(); // フォーカスを戻す
            const start = textInput.selectionStart;
            const end = textInput.selectionEnd;
            const text = textInput.value;
            textInput.value = text.substring(0, start) + '\n' + text.substring(end);
            textInput.selectionStart = textInput.selectionEnd = start + 1;
            renderToCanvas(); // inputイベントが発火しない場合もあるので明示的に呼ぶ
        }
    });

    document.querySelector('button#reset')?.addEventListener('click', () => {
        if (textInput) {
            textInput.value = defaultText;
        }
        setInverted(false); // inverted状態をリセット
        setFontFamily('Arial'); // async 関数だが、ここでは await しない (戻り値はUIに直接影響しない)
                                  // setFontFamily内でrenderToCanvasが呼ばれる
    });

    document.querySelector('button#setInverted')?.addEventListener('click', () => {
        setInverted();
    });

    clearTextButton = document.querySelector('button#clearTextButton');
    if (clearTextButton) {
        clearTextButton.addEventListener('click', () => {
            if (textInput) {
                textInput.value = '';
                // textInputのinputイベントが発火するので、renderToCanvasは自動で呼ばれる想定
                // ただし、明示的に呼んでも良い
                renderToCanvas();
            }
        });
    } else {
        console.warn('Clear text button (button#clearTextButton) not found.');
    }

    // 初期描画
    renderToCanvas();
    console.log("Text Editor Initialized");
}

// DOMが完全にロードされたら初期化処理を実行
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initializeEditor);
} else {
    // DOMContentLoaded has already fired
    initializeEditor();
}

