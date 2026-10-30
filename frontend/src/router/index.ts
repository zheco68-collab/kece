import { createRouter, createWebHistory } from 'vue-router';
import { useUserStore } from '@/stores/user';

// 路由元信息类型声明：meta.roles 是后台路由的角色门槛，meta.title 是页面标题。
declare module 'vue-router' {
  interface RouteMeta {
    roles?: string[];
    title?: string;
    requiresAuth?: boolean;
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    // 前台考生端主站路由（使用 DefaultLayout）
    {
      path: '/',
      component: () => import('@/layouts/DefaultLayout.vue'),
      children: [
        { path: '', name: 'home', component: () => import('@/views/HomeView.vue') },
        {
          path: 'questions',
          name: 'questions',
          component: () => import('@/views/QuestionsView.vue'),
        },
        {
          path: 'questions/:id',
          name: 'question-detail',
          component: () => import('@/views/QuestionDetailView.vue'),
        },
        {
          path: 'questions/:id/submit',
          name: 'question-submit',
          component: () => import('@/views/QuestionSubmitView.vue'),
        },
        {
          path: 'questions/:id/result/:submissionId',
          name: 'question-result',
          component: () => import('@/views/QuestionResultView.vue'),
        },
        {
          path: 'submissions',
          redirect: () => ({ path: '/profile', query: { tab: 'code' } }),
        },
        {
          path: 'submissions/:submissionId',
          name: 'submission-detail',
          component: () => import('@/views/QuestionResultView.vue'),
        },
        {
          path: 'database',
          name: 'database',
          component: () => import('@/views/DatabaseQuestionsView.vue'),
        },
        {
          path: 'database/:id',
          name: 'database-detail',
          component: () => import('@/views/DatabaseQuestionDetailView.vue'),
        },
        {
          path: 'database/:id/submit',
          name: 'database-submit',
          component: () => import('@/views/DatabaseQuestionSubmitView.vue'),
        },
        {
          path: 'database/:id/result/:submissionId',
          name: 'database-result',
          component: () => import('@/views/DatabaseQuestionResultView.vue'),
        },
        {
          path: 'database-submissions',
          redirect: '/submissions',
        },
        {
          path: 'database-submissions/:submissionId',
          name: 'database-submission-detail',
          component: () => import('@/views/DatabaseQuestionResultView.vue'),
        },
        {
          path: 'frontend',
          name: 'frontend',
          component: () => import('@/views/ComingSoonView.vue'),
          meta: { title: '前端' },
        },
        {
          path: 'objective',
          name: 'objective',
          component: () => import('@/views/ObjectiveQuestionsView.vue'),
        },
        {
          path: 'objective/:id',
          name: 'objective-detail',
          component: () => import('@/views/ObjectiveAnswerView.vue'),
        },
        {
          path: 'problem-sets',
          name: 'problem-sets',
          component: () => import('@/views/ProblemSetsView.vue'),
          meta: { title: '精选与专题题集' },
        },
        {
          path: 'problem-sets/:id',
          name: 'problem-set-detail',
          component: () => import('@/views/ProblemSetDetailView.vue'),
          meta: { title: '题集详情' },
        },
        {
          path: 'rankings',
          name: 'rankings',
          component: () => import('@/views/RankingsView.vue'),
          meta: { title: '全域天梯排名' },
        },
        {
          path: 'leaderboard',
          redirect: '/rankings',
        },
        {
          path: 'rank',
          redirect: '/rankings',
        },
        {
          path: 'exams',
          name: 'exams',
          component: () => import('@/views/ExamListView.vue'),
        },
        {
          path: 'exams/:id',
          name: 'exam-session',
          component: () => import('@/views/ExamSessionView.vue'),
          meta: { title: '考试试卷', requiresAuth: true },
        },
        {
          path: 'exams/:id/objective',
          name: 'exam-objective',
          component: () => import('@/views/ExamObjectiveView.vue'),
          meta: { title: '考试客观题合卷', requiresAuth: true },
        },
        {
          path: 'exams/:id/question/:examQuestionId',
          name: 'exam-question',
          component: () => import('@/views/ExamQuestionView.vue'),
          meta: { title: '考试算法题', requiresAuth: true },
        },
        {
          path: 'exams/:id/question/:examQuestionId/submit',
          name: 'exam-question-submit',
          component: () => import('@/views/ExamQuestionSubmitView.vue'),
          meta: { title: '提交考试代码', requiresAuth: true },
        },
        {
          path: 'exams/:id/question/:examQuestionId/result/:submissionId',
          name: 'exam-question-result',
          component: () => import('@/views/ExamQuestionResultView.vue'),
          meta: { title: '考试代码测评结果', requiresAuth: true },
        },
        {
          path: 'exams/:id/db-question/:examQuestionId',
          name: 'exam-db-question',
          component: () => import('@/views/ExamDbQuestionView.vue'),
          meta: { title: '考试数据库题', requiresAuth: true },
        },
        {
          path: 'exams/:id/db-question/:examQuestionId/submit',
          name: 'exam-db-question-submit',
          component: () => import('@/views/ExamDbQuestionSubmitView.vue'),
          meta: { title: '提交考试 SQL', requiresAuth: true },
        },
        {
          path: 'exams/:id/db-question/:examQuestionId/result/:submissionId',
          name: 'exam-db-question-result',
          component: () => import('@/views/ExamDbQuestionResultView.vue'),
          meta: { title: '考试 SQL 测评结果', requiresAuth: true },
        },
        {
          path: 'exams/:id/result',
          name: 'exam-result',
          component: () => import('@/views/ExamResultView.vue'),
          meta: { title: '考试战报与成绩', requiresAuth: true },
        },
        {
          path: 'practice',
          name: 'practice-list',
          component: () => import('@/views/PracticeListView.vue'),
          meta: { title: '随堂课练', requiresAuth: true },
        },
        {
          path: 'practice/:code',
          name: 'practice-detail',
          component: () => import('@/views/PracticeDetailView.vue'),
          meta: { title: '课练作答', requiresAuth: true },
        },
        {
          path: 'practice/:code/objective',
          name: 'practice-objective',
          component: () => import('@/views/PracticeObjectiveView.vue'),
          meta: { title: '课练客观题合卷', requiresAuth: true },
        },
        {
          path: 'practice/:code/question/:id',
          name: 'practice-question',
          component: () => import('@/views/PracticeQuestionView.vue'),
          meta: { title: '课练算法题', requiresAuth: true },
        },
        {
          path: 'practice/:code/question/:id/submit',
          name: 'practice-question-submit',
          component: () => import('@/views/PracticeQuestionSubmitView.vue'),
          meta: { title: '提交课练代码', requiresAuth: true },
        },
        {
          path: 'practice/:code/question/:id/result/:submissionId',
          name: 'practice-question-result',
          component: () => import('@/views/PracticeQuestionResultView.vue'),
          meta: { title: '课练测评结果', requiresAuth: true },
        },
        {
          path: 'practice/:code/db-question/:id',
          name: 'practice-db-question',
          component: () => import('@/views/PracticeDbQuestionView.vue'),
          meta: { title: '课练数据库题', requiresAuth: true },
        },
        {
          path: 'practice/:code/db-question/:id/submit',
          name: 'practice-db-question-submit',
          component: () => import('@/views/PracticeDbQuestionSubmitView.vue'),
          meta: { title: '提交课练 SQL', requiresAuth: true },
        },
        {
          path: 'practice/:code/db-question/:id/result/:submissionId',
          name: 'practice-db-question-result',
          component: () => import('@/views/PracticeDbQuestionResultView.vue'),
          meta: { title: '课练 SQL 测评结果', requiresAuth: true },
        },
        {
          path: 'practice/:code/monitor',
          name: 'practice-monitor',
          component: () => import('@/views/PracticeMonitorView.vue'),
          meta: { title: '课练数据', requiresAuth: true },
        },
        {
          path: 'profile/:id?',
          name: 'profile',
          component: () => import('@/views/ProfileView.vue'),
          meta: { title: '个人主页', requiresAuth: true },
        },
        {
          path: 'announcements',
          name: 'announcements',
          component: () => import('@/views/AnnouncementsBoardView.vue'),
          meta: { title: '平台公告栏' },
        },
        {
          path: 'announcements/:id',
          name: 'announcement-detail',
          component: () => import('@/views/AnnouncementDetailView.vue'),
          meta: { title: '公告详情' },
        },
      ],
    },
    // 独立后台管理端路由（使用独立侧边栏 AdminLayout）
    {
      path: '/admin',
      component: () => import('@/layouts/AdminLayout.vue'),
      redirect: '/admin/questions',
      children: [
        {
          path: 'questions',
          name: 'admin-questions',
          component: () => import('@/views/admin/AdminQuestionsView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '编程题管理' },
        },
        {
          path: 'questions/new',
          name: 'admin-question-new',
          component: () => import('@/views/admin/QuestionEditView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '新建编程题' },
        },
        {
          path: 'questions/:id/edit',
          name: 'admin-question-edit',
          component: () => import('@/views/admin/QuestionEditView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '编辑编程题' },
        },
        {
          path: 'db-questions',
          name: 'admin-db-questions',
          component: () => import('@/views/admin/AdminDbQuestionsView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '数据库题管理' },
        },
        {
          path: 'db-questions/new',
          name: 'admin-db-question-new',
          component: () => import('@/views/admin/DbQuestionEditView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '新建数据库题' },
        },
        {
          path: 'db-questions/:id/edit',
          name: 'admin-db-question-edit',
          component: () => import('@/views/admin/DbQuestionEditView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '编辑数据库题' },
        },
        {
          path: 'objective-questions',
          name: 'admin-objective-questions',
          component: () => import('@/views/admin/AdminObjectiveQuestionsView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '客观题管理' },
        },
        {
          path: 'objective-questions/new',
          name: 'admin-objective-question-new',
          component: () => import('@/views/admin/ObjectiveQuestionEditView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '新建客观题' },
        },
        {
          path: 'objective-questions/:id/edit',
          name: 'admin-objective-question-edit',
          component: () => import('@/views/admin/ObjectiveQuestionEditView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '编辑客观题' },
        },
        {
          path: 'problem-sets',
          name: 'admin-problem-sets',
          component: () => import('@/views/admin/AdminProblemSetsView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '题集管理' },
        },
        {
          path: 'problem-sets/new',
          name: 'admin-problem-set-new',
          component: () => import('@/views/admin/ProblemSetEditView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '新建题集' },
        },
        {
          path: 'problem-sets/:id/edit',
          name: 'admin-problem-set-edit',
          component: () => import('@/views/admin/ProblemSetEditView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '编辑题集' },
        },
        {
          path: 'exams',
          name: 'admin-exams',
          component: () => import('@/views/admin/AdminExamsView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '考试管理' },
        },
        {
          path: 'exams/new',
          name: 'admin-exam-new',
          component: () => import('@/views/admin/ExamEditView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '新建试卷' },
        },
        {
          path: 'exams/:id/edit',
          name: 'admin-exam-edit',
          component: () => import('@/views/admin/ExamEditView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '编辑试卷' },
        },
        {
          path: 'exams/:id/papers',
          name: 'admin-exam-papers',
          component: () => import('@/views/admin/AdminExamPapersView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '考生试卷与成绩总览' },
        },
        {
          path: 'exams/:id/students/:userId/paper',
          name: 'admin-student-paper',
          component: () => import('@/views/admin/AdminStudentPaperView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '考生答卷查阅' },
        },
        {
          path: 'practices',
          name: 'admin-practices',
          component: () => import('@/views/admin/AdminPracticesView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '随堂课练管理' },
        },
        {
          path: 'practices/new',
          name: 'admin-practice-new',
          component: () => import('@/views/admin/PracticeEditView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '发起课堂练习' },
        },
        {
          path: 'users',
          name: 'admin-users',
          component: () => import('@/views/admin/AdminUsersView.vue'),
          meta: { roles: ['ADMIN'], title: '用户管理' },
        },
        {
          path: 'submissions',
          name: 'admin-submissions',
          component: () => import('@/views/admin/AdminSubmissionsView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '提交监控' },
        },
        {
          path: 'announcements',
          name: 'admin-announcements',
          component: () => import('@/views/admin/AdminAnnouncementsView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '公告管理' },
        },
        {
          path: 'announcements/create',
          name: 'admin-announcement-create',
          component: () => import('@/views/admin/AnnouncementEditView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '发布新公告' },
        },
        {
          path: 'announcements/:id/edit',
          name: 'admin-announcement-edit',
          component: () => import('@/views/admin/AnnouncementEditView.vue'),
          meta: { roles: ['ADMIN', 'TEACHER'], title: '编辑公告' },
        },
      ],
    },
    { path: '/login', name: 'login', component: () => import('@/views/AuthView.vue') },
    { path: '/register', name: 'register', component: () => import('@/views/AuthView.vue') },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
    },
  ],
});

// 已登录用户访问登录/注册页时跳回首页；未登录用户访问 requiresAuth 跳登录；带 roles 的页面做角色校验
router.beforeEach((to) => {
  const userStore = useUserStore();
  if ((to.name === 'login' || to.name === 'register') && userStore.isLoggedIn) {
    return { name: 'home' };
  }
  if (to.meta.requiresAuth && !userStore.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }
  const roles = to.meta.roles;
  if (roles && roles.length > 0) {
    if (!userStore.isLoggedIn) {
      return { name: 'login', query: { redirect: to.fullPath } };
    }
    // 角色不符时按不存在处理，不暴露页面是否存在
    if (!roles.some((role) => userStore.hasRole(role))) {
      return { name: 'not-found' };
    }
  }
  return true;
});

export default router;
