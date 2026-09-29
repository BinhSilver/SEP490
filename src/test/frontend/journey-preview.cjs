// Isolated UI preview: real templates/styles/scripts; sample APIs, no database writes.
// Run: node src/test/frontend/journey-preview.cjs, open http://127.0.0.1:4179/survey
const http = require('node:http'), fs = require('node:fs'), path = require('node:path');
const resources = path.resolve(__dirname, '../../main/resources');
const read = p => fs.readFileSync(path.join(resources, p), 'utf8');
const pathData = { id: 1, goal: 'JLPT', currentLevel: 'N5', targetLevel: 'N4', minutesPerDay: 30, totalLessons: 5, completedLessons: 1, totalMinutes: 150, totalDays: 5, phases: [
 { phase:'WARM_UP', lessonCount:3,totalMinutes:90,dayCount:3,items:[
 {lessonId:101,title:'Từ vựng đời sống',dayNumber:1,orderIndex:1,estimatedMinutes:15,completed:true},
 {lessonId:102,title:'Cấu trúc câu cốt lõi A は B です',dayNumber:2,orderIndex:2,estimatedMinutes:15,completed:false,reasonText:'Ôn tập ngữ pháp nền tảng'},
 {lessonId:103,title:'Phản xạ hội thoại chào hỏi',dayNumber:3,orderIndex:3,estimatedMinutes:10,completed:false}]},
 {phase:'GOAL_LEARNING',lessonCount:2,totalMinutes:60,dayCount:2,items:[{lessonId:104,title:'Giao tiếp và đọc hiểu N4',dayNumber:4,orderIndex:4,estimatedMinutes:30,completed:false},{lessonId:105,title:'Luyện nghe trong đời sống',dayNumber:5,orderIndex:5,estimatedMinutes:30,completed:false}]}]};
const lessonData = {id:102,title:'Cấu trúc câu cốt lõi A は B です',unitTitle:'Ngày 03 · Giao tiếp đời sống',skill:'GRAMMAR',status:'PUBLISHED',estimatedMinutes:15,knowledgeTagName:'Câu khẳng định',grammarPoints:[{id:1,pattern:'A は B です',meaning:'Sử dụng cấu trúc để giới thiệu danh tính, quốc tịch và nghề nghiệp.',note:'は viết là ha, đọc là wa khi đánh dấu chủ đề.'}],exampleSentences:[{id:1,grammarPointId:1,textJa:'わたしは学生です。',textVi:'Tôi là học sinh / sinh viên.'},{id:2,grammarPointId:1,textJa:'田中さんは先生です。',textVi:'Anh Tanaka là giáo viên.'}],dialogueLines:[{id:1,text:'はじめまして。',translatedText:'Rất vui được gặp bạn.',roleNameVi:'Tanaka'},{id:2,text:'よろしくお願いします。',translatedText:'Mong được giúp đỡ.',roleNameVi:'Lan',bubbleSide:'phai'}]};
const quizData = {lessonId:102,lessonTitle:'Ghép âm, biến âm và từ vựng đời sống',questions:[{questionId:1,skill:'GRAMMAR',jlptLevel:'N5',title:'Lựa chọn đáp án đúng',questionText:'Đâu là câu diễn đạt: Tôi là sinh viên?',options:['わたしは学生じゃありません。','わたしは学生です。','わたしも学生ですか。','わたしは先生です。']},{questionId:2,skill:'VOCABULARY',jlptLevel:'N5',questionText:'Từ nào mang ý nghĩa buổi sáng?',options:['朝（あさ）','昼（ひる）','夜（よる）','今日（きょう）']}]};
const routes = {'/survey':['smart-test-survey.html','smart-test'],'/learning/path':['learning/path.html','learning-path'],'/learning/lesson/102':['learning/path-lesson.html','textbooks'],'/learning/lesson/102/quiz':['learning/lesson-quiz.html','lesson-quiz']};
function template(route) {
 const [file,css] = routes[route];
 let head = read('templates/fragments/head.html').match(/<head[^>]*>([\s\S]*?)<\/head>/)[1];
 head = head.replace(/<link th:if="[^"]*"[^>]*>/g,'').replace(/<script th:src="@\{\/js\/auth-session.js\}"><\/script>/,'<script>window.JlasAuth={requireLogin:()=>true,isLoggedIn:()=>true,getUser:()=>({fullName:"Học viên mẫu",role:"LEARNER"}),authHeaders:()=>({"Content-Type":"application/json"})};</script>');
 head = head.replace('<!-- Shared shell styles',`<link rel="stylesheet" href="/css/pages/${css}.css"><!-- Shared shell styles`);
 let html = read('templates/'+file).replace(/<head[^>]*>[\s\S]*?<\/head>/,`<head>${head}</head>`);
 if (route === '/smart-test') html = html.replace('</head>', '<script>localStorage.setItem("jlasSmartTestAttemptId","preview");</script></head>');
 if (route === '/warmup') html = read('templates/'+file).replace(/<script th:src="@\{\/js\/auth-session.js\}"><\/script>/, '<script>window.JlasAuth={requireLogin:()=>true,isLoggedIn:()=>true,getUser:()=>({fullName:"Học viên mẫu",role:"LEARNER"}),authHeaders:()=>({"Content-Type":"application/json"})};</script>');
 html = html.replace('data-set-id=${setId}', 'data-set-id=1');
 html = html.replace(/th:(href|src)="@\{([^}]+)\}"/g,'$1="$2"').replace(/th:attr="[^"]*"/g,'data-set-id="1" data-textbook-id="1" data-lesson-id="102" data-quiz-mode="lesson"');
 return html;
}
routes['/smart-test'] = ['smart-test.html','smart-test'];
routes['/'] = ['index.html','home'];
routes['/textbooks'] = ['textbooks/index.html','textbooks'];
routes['/warmup'] = ['learning/warm-up-flashcard.html',null];
routes['/grammar'] = ['learning/grammar.html',null];
http.createServer((req,res)=>{
 const url=new URL(req.url,'http://localhost');
 if(url.pathname.startsWith('/api/')) {
  const data=url.pathname.includes('/paths/current')?pathData:url.pathname.includes('/content')?lessonData:url.pathname.endsWith('/quiz')?quizData:url.pathname.includes('/survey')?{completed:false}:[];
  res.writeHead(200,{'Content-Type':'application/json'});return res.end(JSON.stringify({success:true,data:url.pathname.includes('/smart-tests/')?{attemptId:'preview',status:'IN_PROGRESS',answeredCount:2,totalQuestions:10,selectedSkills:['GRAMMAR','VOCABULARY'],currentQuestion:{...quizData.questions[0],id:1}}:url.pathname.includes('/flashcards/')?{cards:[{front:'学生',reading:'がくせい',meaning:'Sinh viên'},{front:'先生',reading:'せんせい',meaning:'Giáo viên'}]}:data}));
 }
 if(routes[url.pathname]) {res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});return res.end(template(url.pathname));}
 const file=path.resolve(resources,'static','.'+decodeURIComponent(url.pathname));
 if(!file.startsWith(path.join(resources,'static')+path.sep)||!fs.existsSync(file)||!fs.statSync(file).isFile()){res.writeHead(404);return res.end();}
 res.writeHead(200,{'Content-Type':({'.css':'text/css','.js':'text/javascript','.png':'image/png','.svg':'image/svg+xml','.webp':'image/webp'})[path.extname(file)]||'application/octet-stream'});fs.createReadStream(file).pipe(res);
}).listen(4179,'127.0.0.1',()=>console.log('Isolated UI fixtures: http://127.0.0.1:4179/survey'));


