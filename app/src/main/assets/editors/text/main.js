// @ts-check
const canvas = document.querySelector('canvas');
const ctx = canvas.getContext('2d');
const textInput = document.querySelector('textarea#textInput'); // IDも指定してより明確に
const defaultText = textInput.value;
let inverted = false;
let bgColor = 'white';
let textColor = 'black';

let currentFontFace = 'Arial'; // 現在選択されているフォントのCSS Family名を保持 (初期値Arial)

const commonInstance = new NfcEIWCommon(canvas);
const clearCanvas = commonInstance.clearCanvas.bind(commonInstance);

// ▼▼▼ 「消す」ボタンの要素を取得 ▼▼▼
const clearTextButton = document.querySelector('button#clearTextButton');
// ▲▲▲ ここまで追加 ▲▲▲

/**
 * Androidから呼び出される関数。WebViewにフォントを適用する。
 * @param {string} fontCssFamily CSSのfont-family名
 */
// @ts-ignore
function setFontFamily(fontCssFamily) {
    if (typeof fontCssFamily === 'string' && fontCssFamily.trim() !== '') {
        currentFontFace = fontCssFamily;
        console.log(`Font set to by Android: ${currentFontFace}`); // デバッグ用ログ
        renderToCanvas(); // フォントが変更されたらCanvasを再描画
    } else {
        console.warn(`Invalid font family received from Android: ${fontCssFamily}, using current: ${currentFontFace}`);
    }
}


/**
 * Adapted from https://stackoverflow.com/a/65433398/11447682
 * テキストをCanvasの中央にフィットさせて描画
 * @param {string} textToDraw 表示するテキスト
 * @param {number} [paddingW=4] 左右のパディング
 */
function fitAndFillTextCenter(textToDraw, paddingW = 4) {
	if (!textToDraw) {
		clearCanvas(); // clearCanvasを呼び出すように変更 (元からそうなっていればOK)
		drawBg();      // 背景も再描画 (テキストがない場合も背景は維持)
		return;
	}
	const { height: canvasH, width: canvasW } = canvas;
	// currentFontFace を使用してフォントサイズを計算
	let fontSize = getFontSizeToFit(textToDraw, currentFontFace, canvasW - (paddingW * 2), canvasH);
	ctx.fillStyle = textColor;
	ctx.font = fontSize + `px ${currentFontFace}`; // currentFontFace を使用

	ctx.textBaseline = 'middle';
	ctx.textAlign = 'center';

	const x = 0; // 中心揃えのためX座標のオフセットは0
	const y = 0; // ベースラインがmiddleなのでY座標のオフセットも0として計算
	const lines = textToDraw.match(/[^\r\n]+/g); // 改行で分割

	if (lines && lines.length > 0) {
	    for (let i = 0; i < lines.length; i++) {
		    const lineText = lines[i];
            // 各行のY座標を計算 (Canvasの高さを行数+1で割り、i+1番目の位置に配置)
		    let xL = (canvasW - x) / 2; // 各行はCanvasの中央に
		    let yL = y + (canvasH / (lines.length + 1)) * (i + 1);
		    ctx.fillText(lineText, xL, yL);
	    }
    }
}

/**
 * Adapted from https://stackoverflow.com/a/65433398/11447682
 * 指定された幅と高さにテキストを収めるためのフォントサイズを取得
 * @param {string} textToMeasure サイズ計測対象のテキスト
 * @param {string} fontFaceToUse CSSのfont-family名
 * @param {number} targetWidth 利用可能な幅
 * @param {number} targetHeight 利用可能な高さ
 * @returns {number} 計算されたフォントサイズ(px単位)
 */
function getFontSizeToFit(textToMeasure, fontFaceToUse, targetWidth, targetHeight) {
	const lineSpacingPercent = 20; // 行間としてフォントハイトの20%を考慮
	ctx.font = `1px ${fontFaceToUse}`; // 幅計算のために一時的にフォントを設定 (サイズは1pxで)

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
        // テキストが空か改行のみの場合
        maxLineWidth = 0; // 幅は0とする
        // lineCount は 1 のまま (最低1行分の高さを確保するため)
    }

    let fontSizeBasedOnWidth;
    if (maxLineWidth === 0 || targetWidth === 0) { // 幅が0、またはターゲット幅が0なら幅からの計算は最大値
        fontSizeBasedOnWidth = Number.MAX_VALUE;
    } else {
        fontSizeBasedOnWidth = targetWidth / maxLineWidth; // 1pxフォント時の幅に対するターゲット幅の比率
    }

	// 行数と行間を考慮した高さからフォントサイズを計算
	// 各行の高さ = フォントサイズ * (1 + lineSpacingPercent / 100)
	// 全体の高さ = lineCount * フォントサイズ * (1 + lineSpacingPercent / 100)
	// よって フォントサイズ = targetHeight / (lineCount * (1 + lineSpacingPercent / 100))
	let fontSizeBasedOnHeight = targetHeight / (lineCount * (1 + (lineSpacingPercent / 100)));

    // 幅と高さの両方を満たす小さい方のフォントサイズを採用
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
	clearCanvas(); // Canvasをクリア
	drawBg();      // 背景を描画
	// textInput.value が空の場合、fitAndFillTextCenter内で早期リターンし、テキストは描画されない
	fitAndFillTextCenter(textInput.value);
}

// 初期描画 (setFontFamilyが呼ばれる前に一度描画されるが、その後フォント設定時に再描画される)
renderToCanvas();
// setTimeout(renderToCanvas, 200); // Android側からのフォント設定後に再描画されるため、この遅延描画は必須ではない可能性が高い

// Attach listeners
textInput.addEventListener('keyup', renderToCanvas);

// HTMLにこれらのボタンがない場合、以下のリスナーはエラーになる可能性があるため ?. (Optional Chaining) を使用
document.querySelector('button#addLineBreak')?.addEventListener('click', () => {
	textInput.value += '\n';
    renderToCanvas(); // テキスト変更後に再描画
});
document.querySelector('button#reset')?.addEventListener('click', () => {
	textInput.value = defaultText;
	setInverted(false); // 色をリセット
    setFontFamily('Arial'); // フォントをデフォルト(Arial)に戻す
	// renderToCanvas(); // setFontFamily内でrenderToCanvasが呼ばれるので、ここでは不要
});
document.querySelector('button#setInverted')?.addEventListener('click', () => {
	setInverted(); // setInverted内でrenderToCanvasが呼ばれる
});

// ▼▼▼ 「消す」ボタンのイベントリスナーを追加 ▼▼▼
if (clearTextButton) { // ボタン要素が存在するか確認
    clearTextButton.addEventListener('click', () => {
        textInput.value = ''; // テキスト入力欄を空にする
        renderToCanvas();     // Canvasを再描画してクリアされた状態を反映
    });
} else {
    console.warn('Clear text button (button#clearTextButton) not found.'); // 見つからない場合は警告
}
// ▲▲▲ ここまで追加 ▲▲▲

