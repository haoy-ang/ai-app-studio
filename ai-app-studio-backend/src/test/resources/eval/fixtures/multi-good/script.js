// 页脚年份自动更新：避免每年都要手改一次
(function () {
    const footer = document.querySelector('.site-footer p');
    if (!footer) {
        return;
    }
    const year = new Date().getFullYear();
    footer.textContent = footer.textContent + ' · ' + year;
})();
