import { createBrowserRouter, RouterProvider } from 'react-router'
import { AppProviders } from '@/app/providers'
import { AppRouter } from '@/app/router'

const router = createBrowserRouter([{ path: '*', element: <AppRouter /> }])

export default function App() {
  return (
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>
  )
}
