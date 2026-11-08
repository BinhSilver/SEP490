// File chính (air-defense-app.js) chỉ là file mồi: board.html nạp nó kèm ?v=… để xoá cache, mà ES module được
// nhận diện theo URL. Nếu chunk nào import ngược file chính ("../air-defense-app.js", không có ?v=) thì trình duyệt
// chạy file chính lần thứ hai và PixiJS đăng ký extension hai lần ("Extension type texture-source already has a
// handler"). Để toàn bộ code ở bootstrap.tsx, mọi phần dùng chung nằm trong chunk có hash và không chunk nào cần
// import file này. vite.config.ts chặn build nếu điều đó bị phá vỡ.
import("./bootstrap").catch((error) => {
  console.error("Không tải được trò chơi", error);
  const root = document.getElementById("air-defense-root") ?? document.getElementById("game-effects-root");
  if (root) root.textContent = "Không tải được trò chơi. Hãy tải lại trang.";
});
